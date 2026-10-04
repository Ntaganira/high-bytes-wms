package heritier.ntaganira.highbytes.wms.reporting.report;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.report
 * - File       : KpiService.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The control KPIs at a branch: accuracy, turnover, how long releases take, waste, overdue signatures
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The KPIs the Board's finding turns on, measured over the last 90 days at the reader's branch. Each says what it
 * measures and over what, and reads as "—" until there is something to measure: a KPI that flatters by default is
 * worse than a blank. Stock figures leave out places under a live count (V15). Each KPI is shown only to a reader
 * who holds the right of the screen it measures, as each report is.
 */
@Service
@Transactional(readOnly = true)
public class KpiService {

    /** The window every KPI is measured over. */
    static final int DAYS = 90;

    /** One KPI: what it is, its value as read, a line of what it measures, and whether it is off its mark. */
    public record Kpi(String label, String value, String detail, boolean warn) {}

    private final JdbcClient jdbc;

    public KpiService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAuthority('report.view')")
    public List<Kpi> kpis(UUID branchId) {
        List<Kpi> kpis = new ArrayList<>();
        if (holds("count.view")) kpis.add(accuracy(branchId));
        if (holds("stock.view")) kpis.add(turnover(branchId));
        if (holds("dispatch.view")) kpis.add(releaseTime(branchId));
        if (holds("dispatch.view")) kpis.add(gateTime(branchId));
        if (holds("cutting.view")) kpis.add(waste(branchId));
        kpis.add(overdue(branchId));
        return kpis;
    }

    private static boolean holds(String right) {
        return CurrentUser.get().map(u -> u.has(right)).orElse(false);
    }

    /** Of the places counts posted in the window counted, the share found exactly as the book said. */
    private Kpi accuracy(UUID branchId) {
        record Counted(long places, long exact) {}
        Counted c = jdbc.sql("""
                SELECT COUNT(*) AS places, COUNT(*) FILTER (WHERE l.variance_qty = 0) AS exact
                  FROM stock_count_line l JOIN document d ON d.id = l.document_id
                 WHERE d.branch_id = :branch AND d.status = 'POSTED' AND count_book_visible(d.id)
                   AND d.posted_at >= now() - make_interval(days => :days)
                """)
                .param("branch", branchId, Types.OTHER).param("days", DAYS)
                .query((rs, n) -> new Counted(rs.getLong("places"), rs.getLong("exact"))).single();
        if (c.places() == 0) {
            return new Kpi("Inventory accuracy", "—", "No count posted in the last " + DAYS + " days.", false);
        }
        BigDecimal share = BigDecimal.valueOf(c.exact() * 100).divide(BigDecimal.valueOf(c.places()), 1, RoundingMode.HALF_UP);
        return new Kpi("Inventory accuracy", share.toPlainString() + "%",
                c.exact() + " of " + c.places() + " places counted matched the book (target 99%).",
                share.compareTo(new BigDecimal("99.0")) < 0);
    }

    /**
     * Value delivered in the window over today's stock value, annualised: how many times a year the stock turns.
     * Both leave out places under a live count. (The reconciled days' closing values would average better, but they
     * include counted places, and set beside the valuation they would give a counted place's book away.)
     */
    private Kpi turnover(UUID branchId) {
        BigDecimal delivered = jdbc.sql("""
                SELECT COALESCE(SUM(m.value), 0)
                  FROM stock_movement m JOIN transaction_ticket t ON t.document_id = m.document_id
                 WHERE m.branch_id = :branch AND t.movement_type = 'DELIVERY'
                   AND m.business_date >= kigali_today() - :days
                   AND count_freezing(m.item_id, m.location_id) IS NULL
                """)
                .param("branch", branchId, Types.OTHER).param("days", DAYS)
                .query(BigDecimal.class).single();
        BigDecimal stock = jdbc.sql("""
                SELECT COALESCE(SUM(sb.total_value), 0) FROM stock_balance sb JOIN location l ON l.id = sb.location_id
                 WHERE l.branch_id = :branch AND count_freezing(sb.item_id, sb.location_id) IS NULL
                """)
                .param("branch", branchId, Types.OTHER).query(BigDecimal.class).single();
        if (stock.signum() == 0 || delivered.signum() == 0) {
            return new Kpi("Stock turnover", "—", "Nothing delivered, or no stock, in the last " + DAYS + " days.", false);
        }
        BigDecimal turns = delivered.divide(stock, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(365)).divide(BigDecimal.valueOf(DAYS), 1, RoundingMode.HALF_UP);
        return new Kpi("Stock turnover", turns.toPlainString() + "× a year",
                "Value delivered in the last " + DAYS + " days over today's stock value, annualised.", false);
    }

    /** How long an authorization takes from submission to the Internal Controller's release. */
    private Kpi releaseTime(UUID branchId) {
        record Hours(Long n, BigDecimal avg, BigDecimal max) {}
        Hours h = jdbc.sql("""
                SELECT COUNT(*) AS n,
                       round(AVG(EXTRACT(EPOCH FROM d.approved_at - d.submitted_at) / 3600)::numeric, 1) AS avg,
                       round(MAX(EXTRACT(EPOCH FROM d.approved_at - d.submitted_at) / 3600)::numeric, 1) AS max
                  FROM document d JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'DAO'
                 WHERE d.branch_id = :branch AND d.approved_at >= now() - make_interval(days => :days)
                   AND d.submitted_at IS NOT NULL
                """)
                .param("branch", branchId, Types.OTHER).param("days", DAYS)
                .query((rs, n) -> new Hours(rs.getLong("n"), rs.getBigDecimal("avg"), rs.getBigDecimal("max"))).single();
        if (h.n() == 0) {
            return new Kpi("Time to release", "—", "No delivery authorization released in the last " + DAYS + " days.", false);
        }
        return new Kpi("Time to release", h.avg().toPlainString() + " h",
                "Average from submission to the Internal Controller's release, " + h.n()
                        + " authorizations; the longest took " + h.max().toPlainString() + " h.",
                h.avg().compareTo(BigDecimal.valueOf(24)) > 0);
    }

    /** How long released goods wait at the gate: from release (or a cut posted) to the delivery note's posting. */
    private Kpi gateTime(UUID branchId) {
        record Hours(Long n, BigDecimal avg) {}
        Hours h = jdbc.sql("""
                SELECT COUNT(*) AS n,
                       round(AVG(EXTRACT(EPOCH FROM dn.posted_at - COALESCE(a.approved_at, a.posted_at)) / 3600)::numeric, 1) AS avg
                  FROM document dn
                  JOIN delivery_note_authority na ON na.note_id = dn.id
                  JOIN document a                 ON a.id = na.authority_id
                 WHERE dn.branch_id = :branch AND dn.status = 'POSTED'
                   AND dn.posted_at >= now() - make_interval(days => :days)
                """)
                .param("branch", branchId, Types.OTHER).param("days", DAYS)
                .query((rs, n) -> new Hours(rs.getLong("n"), rs.getBigDecimal("avg"))).single();
        if (h.n() == 0) {
            return new Kpi("Time at the gate", "—", "Nothing went through the gate in the last " + DAYS + " days.", false);
        }
        return new Kpi("Time at the gate", h.avg().toPlainString() + " h",
                "Average from release to the delivery note posted at the gate, " + h.n() + " deliveries.", false);
    }

    /** The share of the sheets cut in the window that became waste. */
    private Kpi waste(UUID branchId) {
        record Areas(long orders, BigDecimal sheet, BigDecimal waste) {}
        Areas a = jdbc.sql("""
                SELECT COUNT(*) AS orders, COALESCE(SUM(x.sheet_area), 0) AS sheet, COALESCE(SUM(x.waste_area), 0) AS waste
                  FROM document d
                  JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'CUT'
                  CROSS JOIN LATERAL cut_areas(d.id) x
                 WHERE d.branch_id = :branch AND d.status = 'POSTED'
                   AND d.posted_at >= now() - make_interval(days => :days)
                """)
                .param("branch", branchId, Types.OTHER).param("days", DAYS)
                .query((rs, n) -> new Areas(rs.getLong("orders"), rs.getBigDecimal("sheet"), rs.getBigDecimal("waste")))
                .single();
        if (a.orders() == 0 || a.sheet().signum() == 0) {
            return new Kpi("Cutting waste", "—", "No cutting order posted in the last " + DAYS + " days.", false);
        }
        BigDecimal share = a.waste().multiply(BigDecimal.valueOf(100)).divide(a.sheet(), 1, RoundingMode.HALF_UP);
        return new Kpi("Cutting waste", share.toPlainString() + "%",
                "Of the sheets cut on " + a.orders() + " orders, the area neither delivered nor kept.", false);
    }

    /** Signatures waiting now past their step's escalation time. */
    private Kpi overdue(UUID branchId) {
        long n = jdbc.sql("""
                SELECT COUNT(*)
                  FROM document d
                  JOIN LATERAL (
                       SELECT ws.escalate_after_hours
                         FROM workflow_step ws
                        WHERE ws.workflow_definition_id = d.workflow_definition_id
                          AND NOT EXISTS (SELECT 1 FROM document_approval da
                                           WHERE da.document_id = d.id AND da.workflow_step_id = ws.id)
                        ORDER BY ws.sequence_no LIMIT 1) nx ON TRUE
                 WHERE d.branch_id = :branch AND d.status = 'PENDING' AND nx.escalate_after_hours IS NOT NULL
                   AND COALESCE((SELECT MAX(da.decided_at) FROM document_approval da WHERE da.document_id = d.id),
                                d.created_at) < now() - make_interval(hours => nx.escalate_after_hours)
                """)
                .param("branch", branchId, Types.OTHER)
                .query(Long.class).single();
        return new Kpi("Signatures overdue", String.valueOf(n),
                "Documents waiting now on a step past its chain's escalation time.", n > 0);
    }
}
