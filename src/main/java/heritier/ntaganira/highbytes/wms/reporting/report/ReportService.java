package heritier.ntaganira.highbytes.wms.reporting.report;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.report
 * - File       : ReportService.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The standard reports, each one table read from the ledger and the documents, at one branch
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The standard reports (FR-RPT-01..04), read-only, at the branch the reader works in, behind {@code report.view}.
 * Each is explicit SQL over the ledger and the documents: an aggregate never goes through an ORM.
 *
 * <p>A report reaches no further than the screen it summarises: besides {@code report.view}, each needs that
 * screen's right where the reader works ({@link ReportKind#right}), so a role carrying only {@code report.view}
 * reads nothing of the ledger.
 *
 * <p>A place under a live count shows no book (V15), here as on the stock screens: the valuation, the movement
 * register, the dispatches and the write-offs leave its rows out, never subtract them, and say so. A count's
 * variances are reported only once its verification is signed and it is posted. The daily close is the
 * reconciled record of a day, reported as signed, so to the readers of the close screen only.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    /** The most rows one report carries: past it, the reader narrows the period. */
    static final int MAX_ROWS = 5000;

    private static final String LEFT_OUT =
            "Places under a live count are left out until the count's verification is signed (they show no book).";

    private final JdbcClient jdbc;

    public ReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAuthority('report.view')")
    public ReportTable build(ReportKind kind, UUID branchId, LocalDate from, LocalDate to) {
        if (!readable(kind)) {
            throw new AccessDeniedException("The " + kind.title() + " report needs the " + kind.right() + " right too.");
        }
        String branch = jdbc.sql("SELECT name FROM branch WHERE id = :id").param("id", branchId, Types.OTHER)
                .query(String.class).single();
        return switch (kind) {
            case STOCK_VALUATION -> valuation(branchId, branch);
            case MOVEMENTS -> movements(branchId, branch, from, to);
            case DISPATCHES -> dispatches(branchId, branch, from, to);
            case DAMAGE -> damage(branchId, branch, from, to);
            case VARIANCES -> variances(branchId, branch, from, to);
            case DAILY_CLOSE -> closes(branchId, branch, from, to);
        };
    }

    /** Whether the signed-in user may read this report where they work: the report's own screen's right. */
    public boolean readable(ReportKind kind) {
        return CurrentUser.get().map(u -> u.has(kind.right())).orElse(false);
    }

    // ---- the reports ------------------------------------------------------------------

    private ReportTable valuation(UUID branchId, String branch) {
        List<List<Object>> rows = jdbc.sql("""
                SELECT i.item_code, i.description, l.code AS location, b.bin_code, sb.qty_on_hand, u.code AS unit,
                       CASE WHEN sb.qty_on_hand <> 0 THEN round(sb.total_value / sb.qty_on_hand, 4) END AS unit_cost,
                       sb.total_value
                  FROM stock_balance sb
                  JOIN location l      ON l.id = sb.location_id
                  JOIN item i          ON i.id = sb.item_id
                  JOIN uom u           ON u.id = i.base_uom_id
             LEFT JOIN storage_bin b   ON b.id = sb.storage_bin_id
                 WHERE l.branch_id = :branch AND sb.qty_on_hand <> 0
                   AND count_freezing(sb.item_id, sb.location_id) IS NULL
                 ORDER BY i.item_code, l.code, b.bin_code NULLS FIRST
                 LIMIT :limit
                """)
                .param("branch", branchId, Types.OTHER)
                .param("limit", MAX_ROWS)
                .query((rs, n) -> row(rs.getString("item_code"), rs.getString("description"), rs.getString("location"),
                        rs.getString("bin_code"), rs.getBigDecimal("qty_on_hand"), rs.getString("unit"),
                        rs.getBigDecimal("unit_cost"), rs.getBigDecimal("total_value")))
                .list();
        return table("Stock valuation", branch + " · as at " + today(),
                List.of(ReportTable.code("Item"), ReportTable.text("Description"), ReportTable.code("Location"),
                        ReportTable.code("Bin"), ReportTable.quantity("On hand"), ReportTable.code("Unit"),
                        ReportTable.money("Unit cost (RWF)"), ReportTable.money("Value (RWF)")),
                rows, totalsOf(rows, 8, 7),
                notes(branchId, "Valued at the ledger's moving weighted-average cost, place by place."));
    }

    private ReportTable movements(UUID branchId, String branch, LocalDate from, LocalDate to) {
        List<List<Object>> rows = jdbc.sql("""
                SELECT m.business_date, td.serial_no AS ticket, sd.serial_no AS source, t.movement_type,
                       i.item_code, l.code AS location, m.direction,
                       CASE m.direction WHEN 'IN' THEN m.quantity_base_uom ELSE -m.quantity_base_uom END AS quantity,
                       CASE m.direction WHEN 'IN' THEN m.value ELSE -m.value END AS value,
                       pu.full_name AS posted_by
                  FROM stock_movement m
                  JOIN document td             ON td.id = m.document_id
             LEFT JOIN transaction_ticket t    ON t.document_id = m.document_id
             LEFT JOIN document sd             ON sd.id = t.source_document_id
                  JOIN item i                  ON i.id = m.item_id
                  JOIN location l              ON l.id = m.location_id
                  JOIN app_user pu             ON pu.id = m.posted_by
                 WHERE m.branch_id = :branch AND m.business_date BETWEEN :from AND :to
                   AND count_freezing(m.item_id, m.location_id) IS NULL
                 ORDER BY m.id
                 LIMIT :limit
                """)
                .param("branch", branchId, Types.OTHER)
                .param("from", from, Types.DATE)
                .param("to", to, Types.DATE)
                .param("limit", MAX_ROWS)
                .query((rs, n) -> row(rs.getObject("business_date", LocalDate.class), rs.getString("ticket"),
                        rs.getString("source"), label(rs.getString("movement_type")), rs.getString("item_code"),
                        rs.getString("location"), rs.getString("direction"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("value"), rs.getString("posted_by")))
                .list();
        return table("Movement register", branch + " · " + ReportTable.period(from, to),
                List.of(ReportTable.date("Date"), ReportTable.code("Ticket"), ReportTable.code("Document"),
                        ReportTable.text("Movement"), ReportTable.code("Item"), ReportTable.code("Location"),
                        ReportTable.code("In/out"), ReportTable.quantity("Quantity"), ReportTable.money("Value (RWF)"),
                        ReportTable.text("Posted by")),
                rows, totalsOf(rows, 10, 8),
                notes(branchId, "Quantities and values are signed: in positive, out negative. The total is the "
                        + "net value that moved."));
    }

    private ReportTable dispatches(UUID branchId, String branch, LocalDate from, LocalDate to) {
        List<List<Object>> rows = jdbc.sql("""
                SELECT dn.posted_at, dn.serial_no AS note, a.serial_no AS authority, c.name AS customer,
                       i.item_code, m.quantity_base_uom, m.value, pu.full_name AS posted_by
                  FROM document dn
                  JOIN delivery_note_authority na ON na.note_id = dn.id
                  JOIN document a                 ON a.id = na.authority_id
                  JOIN customer c                 ON c.id = na.customer_id
                  JOIN transaction_ticket t       ON t.source_document_id = dn.id AND t.movement_type = 'DELIVERY'
                  JOIN stock_movement m           ON m.document_id = t.document_id
                  JOIN item i                     ON i.id = m.item_id
             LEFT JOIN app_user pu                ON pu.id = dn.posted_by
                 WHERE dn.branch_id = :branch AND dn.status = 'POSTED'
                   AND (dn.posted_at AT TIME ZONE 'Africa/Kigali')::date BETWEEN :from AND :to
                   AND count_freezing(m.item_id, m.location_id) IS NULL
                 ORDER BY dn.posted_at, dn.serial_no, m.id
                 LIMIT :limit
                """)
                .param("branch", branchId, Types.OTHER)
                .param("from", from, Types.DATE)
                .param("to", to, Types.DATE)
                .param("limit", MAX_ROWS)
                .query((rs, n) -> row(KigaliTime.read(rs, "posted_at"), rs.getString("note"), rs.getString("authority"),
                        rs.getString("customer"), rs.getString("item_code"), rs.getBigDecimal("quantity_base_uom"),
                        rs.getBigDecimal("value"), rs.getString("posted_by")))
                .list();
        return table("Dispatches", branch + " · " + ReportTable.period(from, to),
                List.of(ReportTable.dateTime("Through the gate"), ReportTable.code("Delivery note"),
                        ReportTable.code("Authorization or cutting order"), ReportTable.text("Customer"),
                        ReportTable.code("Item"), ReportTable.quantity("Quantity"), ReportTable.money("Value (RWF)"),
                        ReportTable.text("Posted at the gate by")),
                rows, totalsOf(rows, 8, 6),
                notes(branchId, "Values are the ledger's: the average cost the goods left at."));
    }

    private ReportTable damage(UUID branchId, String branch, LocalDate from, LocalDate to) {
        List<List<Object>> rows = jdbc.sql("""
                SELECT d.posted_at, d.serial_no, r.kind, r.reason_code, i.item_code, l.code AS location,
                       CASE m.direction WHEN 'IN' THEN m.quantity_base_uom ELSE -m.quantity_base_uom END AS quantity,
                       CASE m.direction WHEN 'IN' THEN m.value ELSE -m.value END AS value
                  FROM document d
                  JOIN damage_report r       ON r.document_id = d.id
                  JOIN transaction_ticket t  ON t.source_document_id = d.id AND t.movement_type IN ('DAMAGE', 'RETURN')
                  JOIN stock_movement m      ON m.document_id = t.document_id
                  JOIN item i                ON i.id = m.item_id
                  JOIN location l            ON l.id = m.location_id
                 WHERE d.branch_id = :branch AND d.status = 'POSTED'
                   AND (d.posted_at AT TIME ZONE 'Africa/Kigali')::date BETWEEN :from AND :to
                   AND count_freezing(m.item_id, m.location_id) IS NULL
                 ORDER BY d.posted_at, d.serial_no, m.id
                 LIMIT :limit
                """)
                .param("branch", branchId, Types.OTHER)
                .param("from", from, Types.DATE)
                .param("to", to, Types.DATE)
                .param("limit", MAX_ROWS)
                .query((rs, n) -> row(KigaliTime.read(rs, "posted_at"), rs.getString("serial_no"),
                        label(rs.getString("kind")), rs.getString("reason_code"), rs.getString("item_code"),
                        rs.getString("location"), rs.getBigDecimal("quantity"), rs.getBigDecimal("value")))
                .list();
        return table("Write-offs, losses and returns", branch + " · " + ReportTable.period(from, to),
                List.of(ReportTable.dateTime("Posted"), ReportTable.code("Report"), ReportTable.text("Kind"),
                        ReportTable.text("Reason"), ReportTable.code("Item"), ReportTable.code("Location"),
                        ReportTable.quantity("Quantity"), ReportTable.money("Value (RWF)")),
                rows, totalsOf(rows, 8, 7),
                notes(branchId, "Written off and lost stock is negative; stock a customer returned is positive, "
                        + "into quarantine. A quarantine release moves stock without changing its value and is "
                        + "not listed."));
    }

    private ReportTable variances(UUID branchId, String branch, LocalDate from, LocalDate to) {
        List<List<Object>> rows = jdbc.sql("""
                SELECT d.posted_at, d.serial_no, l.code AS location, i.item_code, cl.book_qty, cl.final_qty,
                       cl.variance_qty,
                       -- The adjustment's own value, from its movement: in positive, out negative.
                       (SELECT CASE m.direction WHEN 'IN' THEN m.value ELSE -m.value END
                          FROM transaction_ticket t
                          JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = cl.line_no
                          JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                         WHERE t.source_document_id = cl.document_id AND t.movement_type = 'ADJUSTMENT') AS signed_value
                  FROM document d
                  JOIN stock_count c       ON c.document_id = d.id
                  JOIN location l          ON l.id = c.location_id
                  JOIN stock_count_line cl ON cl.document_id = d.id
                  JOIN item i              ON i.id = cl.item_id
                 WHERE d.branch_id = :branch AND d.status = 'POSTED'
                   -- The book of a count reaches no page until its verification is signed (V15).
                   AND count_book_visible(d.id)
                   AND cl.variance_qty <> 0
                   AND (d.posted_at AT TIME ZONE 'Africa/Kigali')::date BETWEEN :from AND :to
                 ORDER BY d.posted_at, d.serial_no, cl.line_no
                 LIMIT :limit
                """)
                .param("branch", branchId, Types.OTHER)
                .param("from", from, Types.DATE)
                .param("to", to, Types.DATE)
                .param("limit", MAX_ROWS)
                .query((rs, n) -> row(KigaliTime.read(rs, "posted_at"), rs.getString("serial_no"),
                        rs.getString("location"), rs.getString("item_code"), rs.getBigDecimal("book_qty"),
                        rs.getBigDecimal("final_qty"), rs.getBigDecimal("variance_qty"),
                        rs.getBigDecimal("signed_value")))
                .list();
        return table("Count variances", branch + " · " + ReportTable.period(from, to),
                List.of(ReportTable.dateTime("Posted"), ReportTable.code("Count"), ReportTable.code("Location"),
                        ReportTable.code("Item"), ReportTable.quantity("Book"), ReportTable.quantity("Found"),
                        ReportTable.quantity("Variance"), ReportTable.money("Value adjusted (RWF)")),
                rows, totalsOf(rows, 8, 7),
                List.of("Posted counts only: a count's book is reported once its verification is signed. A "
                        + "shortage is negative, a surplus positive."));
    }

    private ReportTable closes(UUID branchId, String branch, LocalDate from, LocalDate to) {
        List<List<Object>> rows = jdbc.sql("""
                SELECT c.business_date, c.status, c.opening_value, c.receipts_value, c.dispatches_value,
                       c.adjustments_value, c.closing_value, c.movement_count, c.exception_count,
                       ru.full_name AS reconciled_by, c.locked_at
                  FROM daily_close c
             LEFT JOIN app_user ru ON ru.id = c.reconciled_by
                 WHERE c.branch_id = :branch AND c.business_date BETWEEN :from AND :to
                 ORDER BY c.business_date
                 LIMIT :limit
                """)
                .param("branch", branchId, Types.OTHER)
                .param("from", from, Types.DATE)
                .param("to", to, Types.DATE)
                .param("limit", MAX_ROWS)
                .query((rs, n) -> row(rs.getObject("business_date", LocalDate.class), rs.getString("status"),
                        rs.getBigDecimal("opening_value"), rs.getBigDecimal("receipts_value"),
                        rs.getBigDecimal("dispatches_value"), rs.getBigDecimal("adjustments_value"),
                        rs.getBigDecimal("closing_value"), number(rs, "movement_count"), number(rs, "exception_count"),
                        rs.getString("reconciled_by"), KigaliTime.read(rs, "locked_at")))
                .list();
        return table("Daily closes", branch + " · " + ReportTable.period(from, to),
                List.of(ReportTable.date("Day"), ReportTable.text("State"), ReportTable.money("Opening (RWF)"),
                        ReportTable.money("Receipts (RWF)"), ReportTable.money("Dispatches (RWF)"),
                        ReportTable.money("Adjustments (RWF)"), ReportTable.money("Closing (RWF)"),
                        ReportTable.quantity("Movements"), ReportTable.quantity("Exceptions"),
                        ReportTable.text("Reconciled by"), ReportTable.dateTime("Locked")),
                rows, null,
                List.of("Figures are the database's own (close_figures), written when Finance reconciled the day, "
                        + "for the whole branch, places under a count included: the signed record, as on the daily "
                        + "close screen. A day not yet reconciled shows no figures. Opening + receipts − dispatches "
                        + "+ adjustments = closing."));
    }

    // ---- helpers ----------------------------------------------------------------------

    private ReportTable table(String title, String subtitle, List<ReportTable.Column> columns,
                              List<List<Object>> rows, List<Object> totals, List<String> notes) {
        List<String> all = new ArrayList<>(notes);
        boolean cut = rows.size() >= MAX_ROWS;
        if (cut) {
            // A total of the rows shown would read as the whole report's: none is given.
            all.add("Only the first " + MAX_ROWS + " rows are shown, and no total: narrow the period to see "
                    + "the rest.");
        }
        String by = CurrentUser.get().map(u -> u.fullName()).orElse(null);
        return new ReportTable(title, subtitle, columns, rows, rows.isEmpty() || cut ? null : totals, all,
                LocalDateTime.now(KigaliTime.ZONE), by);
    }

    /** The notes of a report that reads stock: and, when a count is live at the branch, that its places are left out. */
    private List<String> notes(UUID branchId, String note) {
        List<String> notes = new ArrayList<>(List.of(note));
        boolean live = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM document d JOIN stock_count c ON c.document_id = d.id
                                WHERE d.branch_id = :branch AND d.status IN ('DRAFT', 'PENDING')
                                  AND NOT count_verification_signed(d.id))
                """).param("branch", branchId, Types.OTHER).query(Boolean.class).single();
        if (live) notes.add(LEFT_OUT);
        return notes;
    }

    /** A totals row of {@code width} cells: "Total" first, the sum of column {@code sumAt} (1-based), else empty. */
    private static List<Object> totalsOf(List<List<Object>> rows, int width, int sumAt) {
        BigDecimal sum = rows.stream().map(r -> r.get(sumAt - 1)).filter(BigDecimal.class::isInstance)
                .map(BigDecimal.class::cast).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Object> totals = new ArrayList<>();
        for (int i = 1; i <= width; i++) {
            totals.add(i == 1 ? "Total" : i == sumAt ? sum : null);
        }
        return totals;
    }

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    private static BigDecimal number(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : new BigDecimal(value.toString());
    }

    /** "TRANSFER_OUT" → "Transfer out". */
    private static String label(String code) {
        if (code == null) return null;
        String lower = code.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String today() {
        return LocalDate.now(KigaliTime.ZONE).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));
    }
}
