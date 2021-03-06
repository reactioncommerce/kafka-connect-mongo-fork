package com.teambition.kafka.connect.mongo.database

import com.mongodb.CursorType
import com.mongodb.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.teambition.kafka.connect.mongo.source.MongoSourceOffset
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.time.format.DateTimeParseException;
import java.time.Instant;
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Connect and tail wait oplog
 * @author Xu Jingxin
 * @param uri mongodb://[user:pwd@]host:port
 * @param db mydb.test
 * @param start
 * @param messages
 * @param executor Executor service for importing data
 */
class DatabaseReader(val uri: String,
                     val db: String,
                     val start: MongoSourceOffset,
                     val messages: ConcurrentLinkedQueue<Document>,
                     private val executor: ExecutorService,
                     private val initialImport: Boolean) : Runnable {
    companion object {
        private val log = LoggerFactory.getLogger(DatabaseReader::class.java)
    }

    private val batchSize = 100
    private val oplog: MongoCollection<Document>
    private val mongoClient: MongoClient = MongoClientLoader.getClient(uri)
    private val mongoDatabase: MongoDatabase
    private var query: Bson? = null
    // Do not write documents until messages are produced into kafka
    // Reduce memory usage
    private val maxMessageSize = 2000

    init {
        mongoDatabase = mongoClient.getDatabase("local")
        oplog = mongoDatabase.getCollection("oplog.rs")

        createQuery()

        log.trace("Start from {}", start)
    }

    /**
     * If start in the old format 'latest_timestamp,inc', use oplog tailing by default
     * If start in the new format 'latest_timestamp,inc,sort_field,finished_import':
     *    if finished_import is true, use oplog tailing and update latest_timestamp
     *    else start mongo collection import from the sort_field first then tailing
     */
    override fun run() {
        if (!start.finishedImport && initialImport) {
            executor.execute { importCollection(db, start.sortField.toString()) }
            executor.awaitTermination(1, TimeUnit.DAYS)
        }
        log.info("Querying oplog on $db from ${start.ts}")
        val documents = oplog
            .find(query)
            .sort(Document("\$natural", 1))
            .projection(Projections.include("ts", "op", "ns", "o", "o2"))
            .cursorType(CursorType.TailableAwait)
            .batchSize(batchSize)
            .maxTime(600, TimeUnit.SECONDS)
            .maxAwaitTime(600, TimeUnit.SECONDS)
            .oplogReplay(true)

        var count = 0
        try {
            for (document in documents) {
                log.trace("Document {}", document!!.toString())
                val doc = handleOp(document)
                count += 1
                if (doc != null) messages.add(doc)
                // Stop pulling data when length of message is too large!
                while (messages.size > maxMessageSize) {
                    log.warn("Message overwhelm! database {}, docs {}, messages {}",
                        db,
                        count,
                        messages.size)
                    Thread.sleep(1000)
                }
                if (count % 1000 == 0) {
                    log.info("Read database {}, docs {}, messages {}, memory usage {}",
                        db,
                        count,
                        messages.size,
                        Runtime.getRuntime().totalMemory())
                }
            }
        } catch (e: Exception) {
            log.error("Connection closed: {}", e.message)
            throw e
        }
    }

    private fun importCollection(db: String, sortField: String) {
        var offsetCount = 0L
        var updatedAt = Date(0L) // default to epoch
        try {
            updatedAt =  Date(Instant.parse(sortField).toEpochMilli())
        } catch (dtpex: DateTimeParseException) {
            // OK to leave default value
        }
        val mongoCollection = getNSCollection(db)
        log.info("Bulk import at $db from position {}", updatedAt)
        mongoCollection
            .find()
            .batchSize(batchSize)
            .filter(Filters.gt("updatedAt", updatedAt))
            .sort(Document("updatedAt", 1))
            .asSequence()
            .forEach { document ->
                messages.add(formatAsOpLog(document))
                updatedAt = document["updatedAt"] as Date
                offsetCount += 1
                while (messages.size > maxMessageSize) {
                    log.warn("Message overwhelm! database {}, docs {}, messages {}",
                        db,
                        offsetCount,
                        messages.size)
                    Thread.sleep(500)
                }
            }
        log.info("Import Task finished, database {}, count {}",
            db,
            offsetCount)
    }

    private fun formatAsOpLog(doc: Document): Document {
        val opDoc = Document()
        opDoc["ts"] = start.ts
        opDoc["ns"] = db
        opDoc["initialImport"] = true
        opDoc["op"] = "i"  // Treat as insertion
        opDoc["o"] = doc
        return opDoc
    }

    /**
     * Handle operations
     * i: keep oplog
     * u: find origin document
     * d: keep oplog
     * @param doc oplog
     * *
     * @return Document
     */
    private fun handleOp(doc: Document): Document? {
        when (doc["op"] as String) {
            "u" -> {
                val updated = findOneById(doc) ?: return null
                doc.append("o", updated)
            }
            else -> {
            }
        }
        return doc
    }

    private fun findOneById(doc: Document): Document? {
        try {
            val nsCollection = getNSCollection(doc["ns"].toString())
            val id = (doc["o2"] as Document)["_id"] as String

            val docs = nsCollection.find(Filters.eq("_id", id)).into(ArrayList<Document>())

            return if (docs.size > 0) docs[0] else null
        } catch (e: Exception) {
            e.printStackTrace()
            log.error("Can not find document: {}", doc)
        }

        return null
    }

    private fun getNSCollection(ns: String): MongoCollection<Document> {
        val dbAndCollection = ns.split("\\.".toRegex()).dropLastWhile(String::isEmpty)
        val nsDB = mongoClient.getDatabase(dbAndCollection[0])
        return nsDB.getCollection(dbAndCollection[1])
    }

    private fun createQuery(): Bson? {
        query = Filters.and(
            Filters.exists("fromMigrate", false),
            Filters.gt("ts", start.ts),
            Filters.or(
                Filters.eq("op", "i"),
                Filters.eq("op", "u"),
                Filters.eq("op", "d")),
            Filters.eq("ns", db))

        return query
    }

}
