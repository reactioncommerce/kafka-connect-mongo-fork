package com.teambition.kafka.connect.mongo.source

import com.teambition.kafka.connect.mongo.tools.CollectionExporter
import com.teambition.kafka.connect.mongo.tools.CronJobDataMap
import com.teambition.kafka.connect.mongo.source.MongoCronSourceConfig.Companion.SCHEDULE_CONFIG
import org.quartz.*
import org.quartz.impl.StdSchedulerFactory
import org.slf4j.LoggerFactory

/**
 * @author Xu Jingxin
 */
class MongoCronSourceTask : AbstractMongoSourceTask() {
    override val log = LoggerFactory.getLogger(MongoCronSourceTask::class.java)!!
    private lateinit var schedule: String
    private lateinit var job: JobDetail
    private val scheduler = StdSchedulerFactory.getDefaultScheduler()!!

    override fun start(props: Map<String, String>) {
        log.info("Start schedule")
        super.start(props)
        schedule = props[SCHEDULE_CONFIG] ?: throw Exception("Invalid schedule!")
        startSchedule()
    }

    override fun stop() {
        log.info("Stop schedule")
        scheduler.deleteJob(job.key)
    }

    private fun startSchedule() {
        if (!scheduler.isStarted) scheduler.start()

        job = JobBuilder.newJob(CollectionExporter::class.java)
            .setJobData(JobDataMap(mapOf("data" to CronJobDataMap(uri, databases, messages))))
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withSchedule(CronScheduleBuilder.cronSchedule(schedule))
            .build()

        scheduler.scheduleJob(job, trigger)
    }
}
