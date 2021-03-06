package com.teambition.kafka.connect.mongo.source

import com.google.common.truth.Truth.assertThat
import org.apache.kafka.connect.data.Schema
import org.bson.BsonTimestamp
import org.bson.BsonUndefined
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.Test
import java.util.*

/**
 * @author Xu Jingxin
 */
class SchemaMapperTest {

    @Test
    fun analyzeStruct() {
        val doc = Document(mapOf(
            "_id" to ObjectId("5b5005ceb9e80fb20d106896"),
            "string" to "string",
            "text" to "text",
            "int" to 10,
            "bool" to false,
            "double" to 1.1,
            "date" to Date(1531970947888), // 2018-07-19T03:29:07.888Z
            "array" to listOf("A", "B"),
            "vacuum" to null,
            "map" to mapOf("k" to "v"),
            "undefined" to BsonUndefined(),
            "camelCase" to "lowercased",
            "doc" to Document(mapOf("objectId" to ObjectId("5b5005ceb9e80fb20d106896"))),
            "docarray" to arrayOf(Document(mapOf("objectId" to ObjectId("5b5005ceb9e80fb20d106896")))),
            "invalidName[1]" to "invalidName"
        ))
        val bson = Document(mapOf(
            "ts" to BsonTimestamp(1531970947, 1),
            "ns" to "d.c",
            "op" to "i",
            "o" to doc
        ))
        // Test types mapping
        SchemaMapper
            .getAnalyzedStruct(bson, "schema")
            .let { struct ->
                assertThat(struct.schema().name()).isEqualTo("schema_d_c")
                assertThat(struct.schema().parameters()["table"]).isEqualTo("base_d_c")
                assertThat(struct["__op"]).isEqualTo("i")
                assertThat(struct["__pkey"]).isEqualTo("5b5005ceb9e80fb20d106896")
                assertThat(struct["__sql"]).isNull()
                assertThat(struct["__ts"]).isEqualTo("2018-07-19T03:29:07.000Z")
                assertThat(struct["_id"]).isEqualTo("5b5005ceb9e80fb20d106896")
                assertThat(struct["string"]).isEqualTo("string")
                assertThat(struct["text"]).isEqualTo("text")
                assertThat(struct["int"]).isEqualTo(10.0)
                assertThat(struct["bool"]).isEqualTo(false)
                assertThat(struct["double"]).isEqualTo(1.1)
                assertThat(struct["date"]).isEqualTo("2018-07-19T03:29:07.888Z")
                assertThat(struct["array"]).isEqualTo("""["A","B"]""")
                assertThat(struct.schema().fields().map { it.name() }).doesNotContain("vacuum")
                assertThat(struct["map"]).isEqualTo("""{"k":"v"}""")
                assertThat(struct.schema().fields().map { it.name() }).doesNotContain("undefined")
                assertThat(struct["camelcase"]).isEqualTo("lowercased")
                assertThat(struct["doc"]).isEqualTo("""{"objectid":"5b5005ceb9e80fb20d106896"}""")
                assertThat(struct["docarray"]).isEqualTo("""[{"objectid":"5b5005ceb9e80fb20d106896"}]""")
                assertThat(struct.schema().field("docarray").schema().type().toString()).isEqualTo("STRING")
                assertThat(struct.schema().field("docarray").schema().parameters()["sqlType"]).isEqualTo("VARCHAR")
                assertThat(struct.schema().fields().map { it.name() }).doesNotContain("invalidname[1]")
            }
    }

    @Test
    fun updateStruct() {
        // Add delete record
        Document(mapOf(
            "ts" to BsonTimestamp(1531970947, 1),
            "ns" to "d.update",
            "op" to "d",
            "o" to Document(mapOf(
                "_id" to ObjectId("5b5005ceb9e80fb20d106896")
            ))
        )).let { SchemaMapper.getAnalyzedStruct(it, "schema_") }

        // Add insert record
        Document(mapOf(
            "ts" to BsonTimestamp(1531970947, 1),
            "ns" to "d.update",
            "op" to "i",
            "o" to Document(mapOf(
                "_id" to ObjectId("5b5005ceb9e80fb20d106896"),
                "name" to "name"
            ))
        )).let { SchemaMapper.getAnalyzedStruct(it, "schema_") }
            .let {
                assertThat(it["_id"]).isEqualTo("5b5005ceb9e80fb20d106896")
                assertThat(it["name"]).isEqualTo("name")
            }
    }

    @Test
    fun conflictStruct() {
        // Field name is double, double sqlType
        // Field date is string, TIMESTAMP sqlType
        Document(mapOf(
            "ts" to BsonTimestamp(1531970947, 1),
            "ns" to "d.conflict",
            "op" to "d",
            "o" to Document(mapOf(
                "name" to 10,
                "date" to BsonTimestamp(1531970947, 1)
            ))
        )).let { SchemaMapper.getAnalyzedStruct(it, "schema_") }
            .let {
                assertThat(it["name"]).isEqualTo(10.0)
                assertThat(it.schema().field("name").schema().type()).isEqualTo(Schema.Type.FLOAT64)
                assertThat(it.schema().field("name").schema().parameters()["sqlType"]).isEqualTo("DOUBLE")
                assertThat(it["date"] as String).matches("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")
                assertThat(it.schema().field("date").schema().type()).isEqualTo(Schema.Type.STRING)
                assertThat(it.schema().field("date").schema().parameters()["sqlType"]).isEqualTo("TIMESTAMP")
            }

        // Discard conflict field, but keep the schema to the old ones
        Document(mapOf(
            "ts" to BsonTimestamp(1531970947, 1),
            "ns" to "d.conflict",
            "op" to "i",
            "o" to Document(mapOf(
                "name" to false,
                "date" to "Tue Jan 02 2018 14:58:24 GMT+0800 (CST)"
            ))
        )).let { SchemaMapper.getAnalyzedStruct(it, "schema_") }
            .let {
                assertThat(it["name"]).isNull()
                assertThat(it.schema().field("name").schema().type()).isEqualTo(Schema.Type.FLOAT64)
                assertThat(it.schema().field("name").schema().parameters()["sqlType"]).isEqualTo("DOUBLE")
                assertThat(it["date"]).isNull()
                assertThat(it.schema().field("date").schema().type()).isEqualTo(Schema.Type.STRING)
                assertThat(it.schema().field("date").schema().parameters()["sqlType"]).isEqualTo("TIMESTAMP")
            }

        // New coming schema type of double will still use double
        Document(mapOf(
            "ts" to BsonTimestamp(1531970947, 1),
            "ns" to "d.conflict",
            "op" to "i",
            "o" to Document(mapOf(
                "name" to 20
            ))
        )).let { SchemaMapper.getAnalyzedStruct(it, "schema_") }
            .let { assertThat(it["name"]).isEqualTo(20.0) }
    }
}

