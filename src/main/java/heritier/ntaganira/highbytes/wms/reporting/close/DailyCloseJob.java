package heritier.ntaganira.highbytes.wms.reporting.close;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.close
 * - File       : DailyCloseJob.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The nightly Quartz job that prepares each branch's daily close for reconciliation
 * </pre>
 */

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Just after midnight in Kigali, prepares the close of every past day on which stock moved and that has none,
 * OPEN and empty, so that it shows as due, and logs as an error every signed day whose ledger no longer reads as
 * it was signed. It signs nothing: a day is reconciled by Finance and locked by the
 * Internal Controller, never by a machine. Persisted in the JDBC job store (V7), so a missed night runs when the
 * application next starts.
 */
@DisallowConcurrentExecution
public class DailyCloseJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(DailyCloseJob.class);

    private DailyCloseService closes;

    /** Set by Spring's job factory, which builds each run's job as a bean. */
    @Autowired
    public void setCloses(DailyCloseService closes) {
        this.closes = closes;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        int prepared = closes.prepareDue();
        int drifted = closes.checkSigned();
        log.info("Nightly close: {} day(s) prepared for reconciliation, {} signed day(s) no longer reading as signed",
                prepared, drifted);
    }
}
