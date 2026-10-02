package heritier.ntaganira.highbytes.wms.reporting.close;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.close
 * - File       : DailyCloseTest.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Closes days end to end: the nightly job, Finance's reconciliation, the lock, the return
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.support.CloseFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against a real PostgreSQL 16 with Flyway V1 to V16, at a branch of the test's own: 10 sheets of glass arrived
 * three days ago and 5 two days ago, at 1,000 a sheet, written straight to the ledger (the only way a past day holds
 * stock), so the balance cache never heard of them and every day reports that as an exception.
 */
class DailyCloseTest extends IntegrationTest {

    @Autowired DailyCloseService closes;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Scheduler scheduler;

    CloseFlow cf;
    LocalDate today;
    LocalDate first;
    LocalDate second;

    @BeforeEach
    void stock() {
        cf = new CloseFlow(fx, jdbc, transactions);
        today = cf.today();
        first = today.minusDays(3);
        second = today.minusDays(2);
        cf.stockOn(first, 10);
        cf.stockOn(second, 5);
    }

    private List<String> closesAtTheBranch() {
        return jdbc.sql("""
                SELECT business_date || ' ' || status || ' ' || COALESCE(closing_value::text, '-')
                  FROM daily_close WHERE branch_id = :branch ORDER BY business_date
                """)
                .param("branch", cf.branch).query(String.class).list();
    }

    private DailyClose reconciled(LocalDate day) {
        fx.actAs(cf.finance, cf.branch);
        return closes.reconcile(cf.branch, day, "Written to the ledger directly: the balance cache never saw it");
    }

    // ---- the nightly job --------------------------------------------------------------------------

    @Test
    void theNightlyJobPreparesEveryPastDayOnWhichStockMovedAndSignsNothing() {
        closes.prepareDue();
        assertThat(closesAtTheBranch()).containsExactly(first + " OPEN -", second + " OPEN -");

        closes.prepareDue();
        assertThat(closesAtTheBranch()).hasSize(2);

        // Written by nobody: the trail names the System.
        assertThat(jdbc.sql("""
                SELECT DISTINCT a.actor_username FROM audit_log a JOIN daily_close c ON c.id = a.entity_id
                 WHERE c.branch_id = :branch AND a.entity_name = 'daily_close'
                """).param("branch", cf.branch).query(String.class).list()).containsExactly("system");
    }

    @Test
    void theJobIsKeptInTheQuartzStoreAndReadsBack() throws SchedulerException {
        assertThat(scheduler.checkExists(DailyCloseSchedule.JOB)).isTrue();
        // Reading the stored job back is what the BYTEA delegate gotcha breaks.
        assertThat(scheduler.getJobDetail(DailyCloseSchedule.JOB).getJobClass()).isEqualTo(DailyCloseJob.class);
        var trigger = scheduler.getTrigger(DailyCloseSchedule.TRIGGER);
        assertThat(trigger).isInstanceOf(CronTrigger.class);
        assertThat(((CronTrigger) trigger).getTimeZone().getID()).isEqualTo("Africa/Kigali");
        assertThat(((CronTrigger) trigger).getCronExpression()).isEqualTo("0 15 0 ? * *");
    }

    // ---- reconciling ------------------------------------------------------------------------------

    @Test
    void financeReconcilesDaysInOrderAndTheDatabaseWritesTheFigures() {
        fx.actAs(cf.finance, cf.branch);
        assertThatThrownBy(() -> closes.reconcile(cf.branch, second, "Out of order"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("Days close in order");
        assertThatThrownBy(() -> closes.reconcile(cf.branch, first, null))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("in the note");

        DailyClose close = reconciled(first);
        assertThat(close.status()).isEqualTo("RECONCILED");
        assertThat(close.signed().opening()).isEqualByComparingTo("0");
        assertThat(close.signed().receipts()).isEqualByComparingTo("10000");
        assertThat(close.signed().closing()).isEqualByComparingTo("10000");
        assertThat(close.signed().movementCount()).isEqualTo(1);
        assertThat(close.exceptionCount()).isPositive();
        assertThat(close.reconciledBy()).isEqualTo(cf.finance);

        // Nothing is dated into a reconciled day, whatever path writes it.
        assertThatThrownBy(() -> cf.moveThroughTheLedger(first))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("closed at this branch");
    }

    @Test
    void aDayIsClosedOnceItIsOverAndADayToComeIsNotRead() {
        fx.actAs(cf.finance, cf.branch);
        assertThatThrownBy(() -> closes.reconcile(cf.branch, today, "Too early"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("not over yet");
        assertThatThrownBy(() -> closes.detail(cf.branch, today.plusDays(1)))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("has not begun");
        assertThat(closes.detail(cf.branch, today).dayOver()).isFalse();
    }

    @Test
    void onlyFinanceReconcilesAndOnlyTheInternalControllerLocks() {
        fx.actAs(cf.manager, cf.branch);
        assertThatThrownBy(() -> closes.reconcile(cf.branch, first, "Mine")).isInstanceOf(AccessDeniedException.class);
        reconciled(first);
        fx.actAs(cf.finance, cf.branch);
        assertThatThrownBy(() -> closes.countersign(cf.branch, first)).isInstanceOf(AccessDeniedException.class);
        fx.actAs(cf.controller, cf.branch);
        assertThatThrownBy(() -> closes.reconcile(cf.branch, second, "Mine")).isInstanceOf(AccessDeniedException.class);
    }

    // ---- locking ----------------------------------------------------------------------------------

    @Test
    void theInternalControllersCountersignatureLocksTheDayForGood() {
        reconciled(first);
        fx.actAs(cf.controller, cf.branch);
        DailyClose locked = closes.countersign(cf.branch, first);
        assertThat(locked.status()).isEqualTo("LOCKED");
        assertThat(locked.lockedAt()).isNotNull();
        assertThat(locked.controllerName()).isNotBlank();

        assertThatThrownBy(() -> closes.returnForReconciliation(cf.branch, first, "Second thoughts"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("only a reconciled day");
        assertThatThrownBy(() -> cf.moveThroughTheLedger(first))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("closed at this branch");
        assertThatThrownBy(() -> jdbc.sql("UPDATE daily_close SET status = 'OPEN' WHERE branch_id = :branch AND business_date = :day")
                .param("branch", cf.branch).param("day", first).update())
                .hasMessageContaining("never reopened");

        // With the first day locked, the second opens where it closed.
        DailyClose next = reconciled(second);
        assertThat(next.signed().opening()).isEqualByComparingTo("10000");
        assertThat(next.signed().closing()).isEqualByComparingTo("15000");
    }

    @Test
    void aReturnedDayIsReconciledAgainAndTheTrailKeepsWhy() {
        reconciled(first);
        fx.actAs(cf.controller, cf.branch);
        assertThatThrownBy(() -> closes.returnForReconciliation(cf.branch, first, "  "))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("needs a reason");
        DailyClose returned = closes.returnForReconciliation(cf.branch, first, "Explain the cache difference");
        assertThat(returned.status()).isEqualTo("OPEN");
        assertThat(returned.returned()).isTrue();
        assertThat(returned.signed()).isNull();
        assertThat(returned.reconciledBy()).isNull();

        assertThat(reconciled(first).status()).isEqualTo("RECONCILED");
        assertThat(jdbc.sql("SELECT reason FROM audit_log WHERE entity_id = :id AND action = 'REJECT' AND reason IS NOT NULL")
                .param("id", returned.id()).query(String.class).list()).contains("Explain the cache difference");
    }

    // ---- reading a day ----------------------------------------------------------------------------

    @Test
    void aDayShowsItsMovementsItsExceptionsAndWhatEachReaderMayDo() {
        fx.actAs(cf.finance, cf.branch);
        var day = closes.detail(cf.branch, first);
        assertThat(day.movements()).hasSize(1);
        assertThat(day.live().closing()).isEqualByComparingTo("10000");
        // The Finance officer reading it posted the day's receipt: reconciling it is an exception, needing a note.
        assertThat(day.findings()).extracting(CloseFinding::kind).contains("CACHE", "RECONCILER_POSTED");
        assertThat(day.actions().canReconcile()).isTrue();
        assertThat(day.actions().noteRequired()).isTrue();
        assertThat(day.postedByMe()).isEqualTo(1);

        var later = closes.detail(cf.branch, second);
        assertThat(later.blockedBy()).isEqualTo(first);
        assertThat(later.actions().canReconcile()).isFalse();
        assertThat(later.actions().reconcileReason()).contains("Days close in order");

        fx.actAs(cf.manager, cf.branch);
        var read = closes.detail(cf.branch, first);
        assertThat(read.actions().canReconcile()).isFalse();
        assertThat(read.actions().reconcileReason()).isNull();
        assertThat(read.findings()).extracting(CloseFinding::kind).doesNotContain("RECONCILER_POSTED");
    }

    @Test
    void whatWasExcusedIsKeptOnTheTrailWholeEvenWhenTheDayIsReturned() {
        String note = "Written to the ledger directly: the balance cache never saw it. " + "x".repeat(500);
        fx.actAs(cf.finance, cf.branch);
        DailyClose close = closes.reconcile(cf.branch, first, note);
        fx.actAs(cf.controller, cf.branch);
        closes.returnForReconciliation(cf.branch, first, "Explain it again");

        List<String> trail = jdbc.sql("SELECT COALESCE(before_state::text, '') || ' ' || COALESCE(after_state::text, '') "
                        + "FROM audit_log WHERE entity_id = :id")
                .param("id", close.id()).query(String.class).list();
        assertThat(trail).anyMatch(s -> s.contains("Exceptions found") && s.contains("Balance cache differs"));
        assertThat(trail).anyMatch(s -> s.contains(note));
    }

    @Test
    void aLockedDayWrittenIntoWithTheLedgersRulesSetAsideIsFlagged() {
        reconciled(first);
        fx.actAs(cf.controller, cf.branch);
        closes.countersign(cf.branch, first);

        cf.stockOn(first, 1);       // as only someone able to set the ledger's triggers aside could

        assertThat(closes.checkSigned()).isPositive();
        fx.actAs(cf.finance, cf.branch);
        assertThat(closes.register(cf.branch).rows())
                .filteredOn(r -> r.businessDate().equals(first))
                .singleElement()
                .satisfies(r -> assertThat(r.drifted()).isTrue());
        assertThat(closes.detail(cf.branch, first).figuresChanged()).isTrue();
    }
}
