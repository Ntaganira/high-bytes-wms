package heritier.ntaganira.highbytes.wms.reporting.close;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.close
 * - File       : DailyCloseService.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The daily close: the register, a day read from the ledger, reconcile, countersign, return, prepare
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.common.db.ContentionException;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The daily close of each branch (V16). Finance reconciles a day that is over: the database writes its figures
 * from the ledger as it signs, and a day with exceptions is signed only with a note on them. The Internal
 * Controller countersigns, which locks the date for good, or returns it with a reason. Days close in order.
 *
 * <p>The rules are the database's; this class asks first where a plain reason helps and otherwise passes the
 * database's own through. Every signature and every refusal is on the trail, under the close.
 *
 * <p>The nightly job ({@link DailyCloseJob}) only prepares closes, OPEN and empty, for each past day on which stock
 * moved. A machine never signs: a day left open stays open, and shows as due.
 */
@Service
@Transactional(readOnly = true)
public class DailyCloseService {

    private static final Logger log = LoggerFactory.getLogger(DailyCloseService.class);

    /** The entity name the audit trail files a close under. */
    public static final String ENTITY = "daily_close";

    private static final String HEADER = """
            SELECT c.id, c.business_date, c.status,
                   c.opening_value, c.receipts_value, c.dispatches_value, c.adjustments_value, c.closing_value,
                   c.movement_count, c.exception_count, c.exceptions_note,
                   c.reconciled_by, ru.full_name AS reconciled_by_name, c.reconciled_at,
                   iu.full_name AS controller_name, c.controller_signed_at, c.locked_at,
                   tu.full_name AS returned_by_name, c.returned_at, c.return_reason
              FROM daily_close c
         LEFT JOIN app_user ru ON ru.id = c.reconciled_by
         LEFT JOIN app_user iu ON iu.id = c.internal_controller_id
         LEFT JOIN app_user tu ON tu.id = c.returned_by
             WHERE c.branch_id = :branch AND c.business_date = :date
            """;

    /** Every close of the branch, and every day after its last locked one on which stock moved and that has none. */
    private static final String REGISTER = """
            WITH last_locked AS (
                SELECT COALESCE(MAX(business_date), '-infinity'::date) AS d
                  FROM daily_close WHERE branch_id = :branch AND status = 'LOCKED'),
            due AS (
                SELECT DISTINCT m.business_date
                  FROM stock_movement m, last_locked ll
                 WHERE m.branch_id = :branch AND m.business_date > ll.d AND m.business_date < kigali_today()),
            days AS (
                SELECT business_date FROM daily_close WHERE branch_id = :branch
                UNION
                SELECT business_date FROM due)
            SELECT d.business_date,
                   COALESCE(c.status, 'OPEN') AS status,
                   c.id IS NOT NULL AS prepared,
                   COALESCE(c.status = 'OPEN' AND c.return_reason IS NOT NULL, FALSE) AS returned,
                   COALESCE(c.movement_count,
                            (SELECT COUNT(*) FROM stock_movement m
                              WHERE m.branch_id = :branch AND m.business_date = d.business_date))::int AS movement_count,
                   c.closing_value, c.exception_count,
                   ru.full_name AS reconciled_by_name, iu.full_name AS controller_name, c.locked_at,
                   dr.close_id IS NOT NULL AS drifted
              FROM days d
         LEFT JOIN daily_close c  ON c.branch_id = :branch AND c.business_date = d.business_date
         LEFT JOIN close_drift(:branch) dr ON dr.close_id = c.id
         LEFT JOIN app_user ru    ON ru.id = c.reconciled_by
         LEFT JOIN app_user iu    ON iu.id = c.internal_controller_id
             ORDER BY d.business_date DESC
             LIMIT 120
            """;

    private static final String MOVEMENTS = """
            SELECT m.id, td.serial_no AS ticket_serial, t.movement_type, sd.id AS source_id, sd.serial_no AS source_serial,
                   i.item_code, i.description, l.code AS location_code, sb.bin_code, m.direction,
                   m.quantity_base_uom, u.code AS uom_code, m.value, pu.full_name AS posted_by_name, m.posted_by,
                   m.movement_at
              FROM stock_movement m
              JOIN item i              ON i.id = m.item_id
              JOIN uom u               ON u.id = i.base_uom_id
              JOIN location l          ON l.id = m.location_id
         LEFT JOIN storage_bin sb      ON sb.id = m.storage_bin_id
         LEFT JOIN document td         ON td.id = m.document_id
         LEFT JOIN transaction_ticket t ON t.document_id = m.document_id
         LEFT JOIN document sd         ON sd.id = t.source_document_id
         LEFT JOIN app_user pu         ON pu.id = m.posted_by
             WHERE m.branch_id = :branch AND m.business_date = :date
             ORDER BY m.id
             LIMIT 2000
            """;

    /** OPEN and empty, for every past day of an active branch on which stock moved, after its last locked day. */
    private static final String PREPARE = """
            INSERT INTO daily_close (branch_id, business_date)
            SELECT DISTINCT m.branch_id, m.business_date
              FROM stock_movement m
              JOIN branch b ON b.id = m.branch_id AND b.is_active
             WHERE m.business_date < kigali_today()
               AND m.business_date > COALESCE((SELECT MAX(c.business_date) FROM daily_close c
                                                WHERE c.branch_id = m.branch_id AND c.status = 'LOCKED'),
                                              '-infinity'::date)
               AND NOT EXISTS (SELECT 1 FROM daily_close c
                                WHERE c.branch_id = m.branch_id AND c.business_date = m.business_date)
            ON CONFLICT (branch_id, business_date) DO NOTHING
            RETURNING id, branch_id, business_date
            """;

    /** The register of a branch's closes, newest first, with what is waiting on whom. */
    public record Register(List<DailyCloseRow> rows, int awaitingReconciliation, int awaitingCountersignature,
                           LocalDate lastLocked, LocalDate today) {}

    /** Everything a day's page needs, read in one pass. */
    public record Detail(DailyClose close, CloseFigures live, List<CloseFinding> findings, List<DayMovement> movements,
                         boolean truncated, boolean dayOver, LocalDate blockedBy, boolean figuresChanged,
                         int postedByReconciler, int postedByMe, CloseActions actions) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final BranchService branches;

    public DailyCloseService(JdbcClient jdbc, AuditService audit, BranchService branches) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.branches = branches;
    }

    // ---- reads -------------------------------------------------------------------------

    @PreAuthorize("hasAuthority('close.view')")
    public Register register(UUID branchId) {
        CurrentUser.requireAt("close.view", branchId);
        List<DailyCloseRow> rows = jdbc.sql(REGISTER)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new DailyCloseRow(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getString("status"),
                        rs.getBoolean("prepared"),
                        rs.getBoolean("returned"),
                        rs.getInt("movement_count"),
                        rs.getBigDecimal("closing_value"),
                        (Integer) rs.getObject("exception_count"),
                        rs.getString("reconciled_by_name"),
                        rs.getString("controller_name"),
                        KigaliTime.read(rs, "locked_at"),
                        rs.getBoolean("drifted")))
                .list();
        int open = (int) rows.stream().filter(r -> "OPEN".equals(r.status())).count();
        int reconciled = (int) rows.stream().filter(r -> "RECONCILED".equals(r.status())).count();
        LocalDate lastLocked = jdbc.sql("SELECT MAX(business_date) FROM daily_close WHERE branch_id = :branch AND status = 'LOCKED'")
                .param("branch", branchId, Types.OTHER)
                .query(LocalDate.class).optional().orElse(null);
        return new Register(rows, open, reconciled, lastLocked, today());
    }

    /**
     * A day at a branch: its close, its figures read from the ledger now, its exceptions, every movement dated in
     * it, and what the signed-in user may do. A day that has not begun is refused; today reads as "so far".
     */
    @PreAuthorize("hasAuthority('close.view')")
    public Detail detail(UUID branchId, LocalDate date) {
        CurrentUser.requireAt("close.view", branchId);
        BranchView branch = branchOf(branchId);
        LocalDate today = today();
        if (date.isAfter(today)) {
            throw new ControlRefusedException(DailyClose.day(date) + " has not begun at " + branch.name()
                    + ", so there is nothing to close.");
        }
        DailyClose close = header(branch, date);
        CloseFigures live = liveFigures(branchId, date);
        // Judged against whoever reconciled it, or, on an open day, against the reader who would.
        UUID reconciler = close.reconciledBy() != null ? close.reconciledBy()
                : close.open() && CurrentUser.holdsAt("close.reconcile", branchId) ? CurrentUser.id() : null;
        List<CloseFinding> findings = findings(branchId, date, reconciler);
        List<DayMovement> movements = jdbc.sql(MOVEMENTS)
                .param("branch", branchId, Types.OTHER)
                .param("date", date)
                .query((rs, n) -> new DayMovement(
                        rs.getLong("id"),
                        rs.getString("ticket_serial"),
                        rs.getString("movement_type"),
                        rs.getObject("source_id", UUID.class),
                        rs.getString("source_serial"),
                        rs.getString("item_code"),
                        rs.getString("description"),
                        rs.getString("location_code"),
                        rs.getString("bin_code"),
                        rs.getString("direction"),
                        rs.getBigDecimal("quantity_base_uom"),
                        rs.getString("uom_code"),
                        rs.getBigDecimal("value"),
                        rs.getString("posted_by_name"),
                        rs.getObject("posted_by", UUID.class),
                        KigaliTime.read(rs, "movement_at")))
                .list();
        boolean dayOver = date.isBefore(today);
        LocalDate blockedBy = unlockedDayBefore(branchId, date);
        boolean changed = close.signed() != null && !close.signed().sameAs(live);
        UUID me = CurrentUser.id();
        int byReconciler = close.reconciledBy() == null ? 0
                : (int) movements.stream().filter(m -> close.reconciledBy().equals(m.postedBy())).count();
        int byMe = me == null ? 0 : (int) movements.stream().filter(m -> me.equals(m.postedBy())).count();
        return new Detail(close, live, findings, movements, movements.size() >= 2000, dayOver, blockedBy, changed,
                byReconciler, byMe, actionsFor(branch, close, live, findings, dayOver, blockedBy, changed));
    }

    // ---- writes ------------------------------------------------------------------------

    /**
     * Reconciles a day that is over: the close is prepared if it is not, and signed as reconciled by the
     * signed-in Finance officer. The database writes the figures and counts the exceptions as it signs; a day with
     * exceptions needs the note.
     */
    @Transactional
    @PreAuthorize("hasAuthority('close.reconcile')")
    public DailyClose reconcile(UUID branchId, LocalDate date, String note) {
        BranchView branch = branchOf(branchId);
        DailyClose before = header(branch, date);
        require("close.reconcile", branch, date, before.id(), "Reconcile");
        String text = blankToNull(note);
        if (text != null && text.length() > 1000) {
            throw refused(branch, date, before.id(), "Reconcile", "A note is at most 1,000 characters.");
        }
        String blocked = reconcileBlocker(branch, before, date.isBefore(today()), unlockedDayBefore(branchId, date));
        if (blocked == null && text == null) {
            int found = findings(branchId, date, CurrentUser.id()).size();
            if (found > 0) blocked = noteNeeded(before.day(), found);
        }
        if (blocked != null) throw refused(branch, date, before.id(), "Reconcile", blocked);
        try {
            jdbc.sql("""
                    INSERT INTO daily_close (branch_id, business_date) VALUES (:branch, :date)
                    ON CONFLICT (branch_id, business_date) DO NOTHING
                    """)
                    .param("branch", branchId, Types.OTHER)
                    .param("date", date)
                    .update();
            int rows = jdbc.sql("""
                    UPDATE daily_close SET status = 'RECONCILED', reconciled_by = :me, exceptions_note = :note
                     WHERE branch_id = :branch AND business_date = :date AND status = 'OPEN'
                    """)
                    .param("me", CurrentUser.id(), Types.OTHER)
                    .param("note", text)
                    .param("branch", branchId, Types.OTHER)
                    .param("date", date)
                    .update();
            if (rows == 0) {
                throw new ControlRefusedException("The close of " + before.day() + " is no longer open: someone has "
                        + "signed it since you opened the page. Reload it.");
            }
        } catch (ContentionException e) {
            throw e;
        } catch (ControlRefusedException e) {
            throw refused(branch, date, before.id(), "Reconcile", e.getMessage());
        } catch (DataAccessException e) {
            throw refusedBy(branch, date, before.id(), "Reconcile", e);
        }
        DailyClose after = header(branch, date);
        CloseFigures f = after.signed();
        // What was excused, as it read when it was signed: the exceptions are read live afterwards, and a return
        // clears the note. The note is kept whole here; the reason column holds 400 characters.
        String excused = findings(branchId, date, CurrentUser.id()).stream()
                .map(x -> x.title() + ": " + x.detail())
                .reduce((a, b) -> a + "\n" + b).orElse("none");
        audit.recordInTransaction(ENTITY, after.id(), after.label(), AuditAction.APPROVE, AuditSnapshot.of()
                        .field("Status", before.status(), "RECONCILED")
                        .value("Opening (RWF)", f.opening())
                        .value("Receipts (RWF)", f.receipts())
                        .value("Dispatches (RWF)", f.dispatches())
                        .value("Adjustments (RWF)", f.adjustments())
                        .value("Closing (RWF)", f.closing())
                        .value("Movements", f.movementCount())
                        .value("Exceptions", after.exceptionCount())
                        .value("Exceptions found", excused)
                        .value("Note", text),
                branch, text);
        return after;
    }

    /**
     * Countersigns a reconciled day, which locks it: from then on the ledger refuses anything dated into it or
     * before it, and the close never changes again. By a holder of close.lock who did not reconcile it, while the
     * ledger still reads as it was reconciled.
     */
    @Transactional
    @PreAuthorize("hasAuthority('close.lock')")
    public DailyClose countersign(UUID branchId, LocalDate date) {
        BranchView branch = branchOf(branchId);
        DailyClose before = header(branch, date);
        require("close.lock", branch, date, before.id(), "Countersign");
        String blocked = countersignBlocker(before, unlockedDayBefore(branchId, date),
                before.signed() != null && !before.signed().sameAs(liveFigures(branchId, date)));
        if (blocked != null) throw refused(branch, date, before.id(), "Countersign", blocked);
        try {
            int rows = jdbc.sql("""
                    UPDATE daily_close SET status = 'LOCKED', internal_controller_id = :me
                     WHERE branch_id = :branch AND business_date = :date AND status = 'RECONCILED'
                    """)
                    .param("me", CurrentUser.id(), Types.OTHER)
                    .param("branch", branchId, Types.OTHER)
                    .param("date", date)
                    .update();
            if (rows == 0) {
                throw new ControlRefusedException("The close of " + before.day() + " is no longer awaiting its "
                        + "countersignature: it was returned or signed since you opened the page. Reload it.");
            }
        } catch (ContentionException e) {
            throw e;
        } catch (ControlRefusedException e) {
            throw refused(branch, date, before.id(), "Countersign", e.getMessage());
        } catch (DataAccessException e) {
            throw refusedBy(branch, date, before.id(), "Countersign", e);
        }
        DailyClose after = header(branch, date);
        audit.recordInTransaction(ENTITY, after.id(), after.label(), AuditAction.APPROVE, AuditSnapshot.of()
                        .field("Status", "RECONCILED", "LOCKED")
                        .value("Closing (RWF)", after.signed().closing())
                        .value("Reconciled by", after.reconciledByName()),
                branch, null);
        return after;
    }

    /**
     * Returns a reconciled day to be reconciled again, with a reason: its reconciliation is cleared. By a holder of
     * close.lock who did not reconcile it.
     */
    @Transactional
    @PreAuthorize("hasAuthority('close.lock')")
    public DailyClose returnForReconciliation(UUID branchId, LocalDate date, String reason) {
        BranchView branch = branchOf(branchId);
        DailyClose before = header(branch, date);
        require("close.lock", branch, date, before.id(), "Return");
        String text = blankToNull(reason);
        String blocked = !before.reconciled() ? "The close of " + before.day() + " is " + before.status()
                + ": only a reconciled day awaiting its countersignature is returned."
                : Objects.equals(before.reconciledBy(), CurrentUser.id()) ? "You reconciled the close of "
                        + before.day() + ", so you cannot also return it."
                : text == null ? "Returning the close of " + before.day() + " needs a reason: say what is to be "
                        + "looked at again."
                : text.length() > 400 ? "A reason is at most 400 characters." : null;
        if (blocked != null) throw refused(branch, date, before.id(), "Return", blocked);
        try {
            int rows = jdbc.sql("""
                    UPDATE daily_close SET status = 'OPEN', returned_by = :me, return_reason = :reason
                     WHERE branch_id = :branch AND business_date = :date AND status = 'RECONCILED'
                    """)
                    .param("me", CurrentUser.id(), Types.OTHER)
                    .param("reason", text)
                    .param("branch", branchId, Types.OTHER)
                    .param("date", date)
                    .update();
            if (rows == 0) {
                throw new ControlRefusedException("The close of " + before.day() + " is no longer awaiting its "
                        + "countersignature. Reload it.");
            }
        } catch (ContentionException e) {
            throw e;
        } catch (ControlRefusedException e) {
            throw refused(branch, date, before.id(), "Return", e.getMessage());
        } catch (DataAccessException e) {
            throw refusedBy(branch, date, before.id(), "Return", e);
        }
        DailyClose after = header(branch, date);
        audit.recordInTransaction(ENTITY, after.id(), after.label(), AuditAction.REJECT, AuditSnapshot.of()
                        .field("Status", "RECONCILED", "OPEN")
                        .field("Reconciled by", before.reconciledByName(), null)
                        .field("Note", before.exceptionsNote(), null),
                branch, text);
        return after;
    }

    /**
     * Prepares, OPEN and empty, the close of every past day at an active branch on which stock moved and that has
     * none, after the branch's last locked day. Signs nothing. Run nightly by {@link DailyCloseJob}, as nobody: the
     * trail names the System. Returns how many it prepared.
     */
    @Transactional
    int prepareDue() {
        record Prepared(UUID id, UUID branchId, LocalDate date) {}
        List<Prepared> prepared = jdbc.sql(PREPARE)
                .query((rs, n) -> new Prepared(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getObject("business_date", LocalDate.class)))
                .list();
        for (Prepared p : prepared) {
            BranchView branch = branches.findById(p.branchId()).orElse(null);
            audit.recordInTransaction(ENTITY, p.id(),
                    DailyClose.label(branch == null ? "?" : branch.code(), p.date()), AuditAction.CREATE,
                    AuditSnapshot.of().field("Status", null, "OPEN"), branch,
                    "Prepared by the nightly close job: stock moved that day.");
        }
        if (!prepared.isEmpty()) log.info("Prepared {} daily close(s) awaiting reconciliation", prepared.size());
        return prepared.size();
    }

    /**
     * Every reconciled or locked close, at any branch, whose ledger no longer reads as it was signed
     * ({@code close_drift}): only someone able to set the ledger's triggers aside can cause one. Logged as an error
     * each night by {@link DailyCloseJob}, and flagged on the register for whoever opens it. Returns how many.
     */
    int checkSigned() {
        record Drift(String branch, LocalDate date, String status, Object signed, Object ledger, int signedCount,
                     int ledgerCount) {}
        List<Drift> drifts = jdbc.sql("""
                SELECT b.code, d.business_date, d.status, d.signed_closing, d.ledger_closing, d.signed_count, d.ledger_count
                  FROM close_drift(NULL) d JOIN branch b ON b.id = d.branch_id
                 ORDER BY b.code, d.business_date
                """)
                .query((rs, n) -> new Drift(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getInt(6), rs.getInt(7)))
                .list();
        for (Drift d : drifts) {
            log.error("The {} close of {} at {} no longer reads as signed: closing {} with {} movements when signed, "
                            + "{} with {} now. Something was written into a closed day with the ledger's rules set aside.",
                    d.status().toLowerCase(), d.date(), d.branch(), d.signed(), d.signedCount(), d.ledger(),
                    d.ledgerCount());
        }
        return drifts.size();
    }

    // ---- helpers -----------------------------------------------------------------------

    private CloseActions actionsFor(BranchView branch, DailyClose close, CloseFigures live, List<CloseFinding> findings,
                                    boolean dayOver, LocalDate blockedBy, boolean changed) {
        boolean reconcileRight = CurrentUser.holdsAt("close.reconcile", branch.id());
        boolean lockRight = CurrentUser.holdsAt("close.lock", branch.id());

        boolean canReconcile = false;
        String reconcileReason = null;
        if (close.open() && reconcileRight) {
            reconcileReason = reconcileBlocker(branch, close, dayOver, blockedBy);
            canReconcile = reconcileReason == null;
        }

        boolean canCountersign = false;
        String countersignReason = null;
        boolean canReturn = false;
        if (close.reconciled() && lockRight) {
            countersignReason = countersignBlocker(close, blockedBy, changed);
            canCountersign = countersignReason == null;
            canReturn = !Objects.equals(close.reconciledBy(), CurrentUser.id());
        }
        return new CloseActions(canReconcile, reconcileReason, !findings.isEmpty(), canCountersign, countersignReason,
                canReturn);
    }

    /** Why this day may not be reconciled now, or null. (The note is judged when it is given.) */
    private static String reconcileBlocker(BranchView branch, DailyClose close, boolean dayOver, LocalDate blockedBy) {
        if (close.locked()) return "The close of " + close.day() + " is locked.";
        if (close.reconciled()) return "The close of " + close.day() + " was reconciled by " + close.reconciledByName()
                + " and awaits the Internal Controller's countersignature.";
        if (!dayOver) return close.day() + " is not over yet in Kigali: a day is reconciled once it has ended.";
        if (blockedBy != null) {
            return "Stock moved at " + branch.name() + " on " + DailyClose.day(blockedBy) + ", which is not locked "
                    + "yet. Days close in order, so that day is reconciled and locked first.";
        }
        return null;
    }

    /** Why the signed-in user may not countersign this day, or null. */
    private static String countersignBlocker(DailyClose close, LocalDate blockedBy, boolean changed) {
        if (!close.reconciled()) return "The close of " + close.day() + " is " + close.status()
                + ": a day is countersigned once Finance has reconciled it.";
        if (Objects.equals(close.reconciledBy(), CurrentUser.id())) return "You reconciled the close of " + close.day()
                + ", so you cannot also countersign it. The day is locked by someone who did not reconcile it.";
        if (blockedBy != null) return "An earlier day on which stock moved is not locked yet. Days close in order.";
        if (changed) return "The ledger for " + close.day() + " no longer reads as it was reconciled. Return it to "
                + "be reconciled again.";
        return null;
    }

    private static String noteNeeded(String day, int findings) {
        return "The close of " + day + " has " + findings + (findings == 1 ? " exception" : " exceptions")
                + ". Say what " + (findings == 1 ? "it is" : "they are") + " and why the day still closes, in the note.";
    }

    private DailyClose header(BranchView branch, LocalDate date) {
        return jdbc.sql(HEADER)
                .param("branch", branch.id(), Types.OTHER)
                .param("date", date)
                .query((rs, n) -> mapHeader(rs, branch))
                .optional()
                .orElseGet(() -> new DailyClose(null, branch.id(), branch.code(), branch.name(), date, "OPEN", false,
                        null, null, null, null, null, null, null, null, null, null, null, null));
    }

    private static DailyClose mapHeader(ResultSet rs, BranchView branch) throws SQLException {
        boolean signed = rs.getBigDecimal("closing_value") != null;
        return new DailyClose(
                rs.getObject("id", UUID.class),
                branch.id(),
                branch.code(),
                branch.name(),
                rs.getObject("business_date", LocalDate.class),
                rs.getString("status"),
                true,
                signed ? new CloseFigures(rs.getBigDecimal("opening_value"), rs.getBigDecimal("receipts_value"),
                        rs.getBigDecimal("dispatches_value"), rs.getBigDecimal("adjustments_value"),
                        rs.getBigDecimal("closing_value"), rs.getInt("movement_count")) : null,
                (Integer) rs.getObject("exception_count"),
                rs.getString("exceptions_note"),
                rs.getObject("reconciled_by", UUID.class),
                rs.getString("reconciled_by_name"),
                KigaliTime.read(rs, "reconciled_at"),
                rs.getString("controller_name"),
                KigaliTime.read(rs, "controller_signed_at"),
                KigaliTime.read(rs, "locked_at"),
                rs.getString("returned_by_name"),
                KigaliTime.read(rs, "returned_at"),
                rs.getString("return_reason"));
    }

    private CloseFigures liveFigures(UUID branchId, LocalDate date) {
        return jdbc.sql("SELECT * FROM close_figures(:branch, :date)")
                .param("branch", branchId, Types.OTHER)
                .param("date", date)
                .query((rs, n) -> new CloseFigures(rs.getBigDecimal("opening_value"), rs.getBigDecimal("receipts_value"),
                        rs.getBigDecimal("dispatches_value"), rs.getBigDecimal("adjustments_value"),
                        rs.getBigDecimal("closing_value"), rs.getInt("movement_count")))
                .single();
    }

    /** The day's exceptions; with a reconciler named, including whether they posted any of its movements. */
    private List<CloseFinding> findings(UUID branchId, LocalDate date, UUID reconciler) {
        return jdbc.sql("SELECT kind, detail FROM close_exceptions(:branch, :date, :reconciler::uuid)")
                .param("branch", branchId, Types.OTHER)
                .param("date", date)
                .param("reconciler", reconciler, Types.OTHER)
                .query((rs, n) -> new CloseFinding(rs.getString("kind"), rs.getString("detail")))
                .list();
    }

    private LocalDate unlockedDayBefore(UUID branchId, LocalDate date) {
        return jdbc.sql("SELECT close_unlocked_day_before(:branch, :date)")
                .param("branch", branchId, Types.OTHER)
                .param("date", date)
                .query(LocalDate.class).optional().orElse(null);
    }

    private LocalDate today() {
        return jdbc.sql("SELECT kigali_today()").query(LocalDate.class).single();
    }

    private BranchView branchOf(UUID branchId) {
        return branches.findById(branchId)
                .orElseThrow(() -> new ControlRefusedException("That branch does not exist."));
    }

    /** Requires a right at the branch for an action; a refusal is recorded (it survives the rollback) before the 403. */
    private void require(String right, BranchView branch, LocalDate date, UUID closeId, String attempt) {
        try {
            CurrentUser.requireAt(right, branch.id());
        } catch (AccessDeniedException e) {
            audit.recordRefusal(ENTITY, closeId, DailyClose.label(branch.code(), date),
                    AuditSnapshot.of().value("Attempted", attempt), branch,
                    "The " + right + " right is not held at " + branch.name() + ".");
            throw e;
        }
    }

    private ControlRefusedException refused(BranchView branch, LocalDate date, UUID closeId, String attempt, String reason) {
        audit.recordRefusal(ENTITY, closeId, DailyClose.label(branch.code(), date),
                AuditSnapshot.of().value("Attempted", attempt), branch, reason);
        return new ControlRefusedException(reason);
    }

    private RuntimeException refusedBy(BranchView branch, LocalDate date, UUID closeId, String attempt,
                                       DataAccessException failure) {
        if (DbRefusal.isContention(failure)) {
            log.error("{} of the close of {} lost a race; asked to try again", attempt, date, failure);
            return new ContentionException();
        }
        return DbRefusal.reason(failure)
                .<RuntimeException>map(reason -> refused(branch, date, closeId, attempt, reason))
                .orElse(failure);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
