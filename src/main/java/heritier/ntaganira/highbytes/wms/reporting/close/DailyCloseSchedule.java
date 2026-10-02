package heritier.ntaganira.highbytes.wms.reporting.close;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.close
 * - File       : DailyCloseSchedule.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Registers the nightly close job and its trigger with the Quartz scheduler
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * The nightly close job, at 00:15 in Kigali. Spring Boot hands both beans to the scheduler, which stores them in
 * the JDBC job store; {@code spring.quartz.overwrite-existing-jobs} lets a changed schedule replace the stored one.
 * A run missed while the application was down fires once when it starts again.
 */
@Configuration
public class DailyCloseSchedule {

    public static final JobKey JOB = JobKey.jobKey("daily-close-prepare", "reporting");
    public static final TriggerKey TRIGGER = TriggerKey.triggerKey("daily-close-prepare", "reporting");

    @Bean
    public JobDetail dailyCloseJobDetail() {
        return JobBuilder.newJob(DailyCloseJob.class)
                .withIdentity(JOB)
                .withDescription("Prepares the close of every past day on which stock moved, for Finance to reconcile")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger dailyCloseTrigger(JobDetail dailyCloseJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(dailyCloseJobDetail)
                .withIdentity(TRIGGER)
                .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(0, 15)
                        .inTimeZone(TimeZone.getTimeZone(KigaliTime.ZONE))
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
    }
}
