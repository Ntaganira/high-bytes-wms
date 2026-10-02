package heritier.ntaganira.highbytes.wms.dashboard;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.dashboard
 * - File       : DashboardService.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Dashboard reads: KPIs, recent movements, low stock, chart
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.dashboard.DashboardViews.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard reads.
 *
 * <p>These are ledger aggregates, so they are explicit SQL rather than JPA:
 * a balance per item per location over a date window is the shape Hibernate
 * turns into either an N+1 or a fetch-graph puzzle.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final JdbcClient jdbc;

    public DashboardService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- KPIs -----------------------------------------------------------

    private static final String STOCK_VALUE = """
            SELECT COALESCE(SUM(sb.total_value), 0)
              FROM stock_balance sb
              JOIN location l ON l.id = sb.location_id
             WHERE l.branch_id = :branchId
               -- Left out, never subtracted: a total must not give a blind count's book away (V15).
               AND count_freezing(sb.item_id, sb.location_id) IS NULL
            """;

    private static final String RECEIVED_SINCE = """
            SELECT COALESCE(SUM(m.quantity_base_uom), 0)
              FROM stock_movement m
             WHERE m.branch_id = :branchId
               AND m.direction = 'IN'
               AND m.business_date >= :since
               AND count_freezing(m.item_id, m.location_id) IS NULL
            """;

    private static final String RECEIPT_NOTES_SINCE = """
            SELECT COUNT(*)
              FROM document d
              JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'GRN'
             WHERE d.branch_id = :branchId
               AND d.status = 'POSTED'
               AND d.document_date >= :since
            """;

    private static final String RELEASES_HELD = """
            SELECT COUNT(*)                                                         AS held,
                   COUNT(*) FILTER (WHERE d.created_at < now() - INTERVAL '24 hours') AS over_24h
              FROM document d
              JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'DAO'
             WHERE d.branch_id = :branchId
               AND d.status IN ('DRAFT','PENDING')
            """;

    /**
     * Inventory accuracy: of the places counted by counts posted in the last 90 days, the share where what was
     * found matched the book. Only posted counts: a count still open has neither its final quantities nor, while
     * it is blind, a book anyone may read.
     */
    private static final String ACCURACY = """
            SELECT COUNT(*) AS places, COUNT(*) FILTER (WHERE l.variance_qty = 0) AS exact
              FROM stock_count_line l
              JOIN document d ON d.id = l.document_id
             WHERE d.branch_id = :branchId
               AND d.status = 'POSTED'
               AND d.posted_at >= now() - INTERVAL '90 days'
            """;

    /** The target the dashboard measures accuracy against. */
    static final BigDecimal ACCURACY_TARGET = new BigDecimal("99.0");

    /** The value of the stock at the branch, places under a live count left out. */
    public BigDecimal stockValue(UUID branchId) {
        return jdbc.sql(STOCK_VALUE).param("branchId", branchId)
                .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    public Kpi kpis(UUID branchId) {
        BigDecimal stockValue = stockValue(branchId);

        LocalDate weekAgo = LocalDate.now().minusDays(7);

        long received = jdbc.sql(RECEIVED_SINCE)
                .param("branchId", branchId).param("since", weekAgo)
                .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO).longValue();

        int notes = jdbc.sql(RECEIPT_NOTES_SINCE)
                .param("branchId", branchId).param("since", weekAgo)
                .query(Integer.class).optional().orElse(0);

        int[] holds = jdbc.sql(RELEASES_HELD).param("branchId", branchId)
                .query((rs, n) -> new int[]{rs.getInt("held"), rs.getInt("over_24h")})
                .optional().orElse(new int[]{0, 0});

        // With no posted count yet it reads as not yet measured rather than
        // 100%, because a KPI that flatters by default is worse than a blank.
        int[] counted = jdbc.sql(ACCURACY).param("branchId", branchId)
                .query((rs, n) -> new int[]{rs.getInt("places"), rs.getInt("exact")})
                .optional().orElse(new int[]{0, 0});
        String accuracy = "—";
        boolean onTarget = true;
        if (counted[0] > 0) {
            BigDecimal share = new BigDecimal(counted[1] * 100L)
                    .divide(new BigDecimal(counted[0]), 1, RoundingMode.HALF_UP);
            accuracy = share.toPlainString();
            onTarget = share.compareTo(ACCURACY_TARGET) >= 0;
        }

        return new Kpi(
                billions(stockValue),
                "0.0", true,
                received, notes,
                holds[0], holds[1],
                accuracy, onTarget);
    }

    private String billions(BigDecimal value) {
        return value.divide(new BigDecimal("1000000000"), 2, RoundingMode.HALF_UP).toPlainString();
    }

    // ---- Recent movements ---------------------------------------------------

    private static final String RECENT = """
            SELECT d.id, d.serial_no, d.status, dt.name AS doc_type,
                   i.description AS item_description,
                   m.quantity_base_uom
              FROM stock_movement m
              JOIN document d       ON d.id = m.document_id
              JOIN document_type dt ON dt.id = d.document_type_id
              JOIN item i           ON i.id = m.item_id
             WHERE m.branch_id = :branchId
               -- No movement at a place under a live count (V15).
               AND count_freezing(m.item_id, m.location_id) IS NULL
             ORDER BY m.movement_at DESC
             LIMIT 6
            """;

    public List<RecentMovement> recentMovements(UUID branchId) {
        return jdbc.sql(RECENT).param("branchId", branchId)
                .query((rs, n) -> new RecentMovement(
                        rs.getObject("id", UUID.class),
                        rs.getString("serial_no"),
                        rs.getString("doc_type"),
                        rs.getString("item_description"),
                        rs.getBigDecimal("quantity_base_uom"),
                        rs.getString("status")))
                .list();
    }

    // ---- Below reorder -------------------------------------------------------

    private static final String LOW_STOCK = """
            SELECT i.description, i.reorder_level,
                   COALESCE(sb.qty_on_hand, 0) AS on_hand,
                   COALESCE(bin.bin_code, l.code) AS bin_code
              FROM item i
              JOIN stock_balance sb ON sb.item_id = i.id
              JOIN location l       ON l.id = sb.location_id
         LEFT JOIN storage_bin bin  ON bin.id = sb.storage_bin_id
             WHERE l.branch_id = :branchId
               AND i.reorder_level IS NOT NULL
               AND sb.qty_on_hand < i.reorder_level
               AND i.is_active
               AND count_freezing(sb.item_id, sb.location_id) IS NULL
             ORDER BY (sb.qty_on_hand / NULLIF(i.reorder_level, 0))
             LIMIT 6
            """;

    public List<LowStockRow> lowStock(UUID branchId) {
        return jdbc.sql(LOW_STOCK).param("branchId", branchId)
                .query((rs, n) -> {
                    BigDecimal onHand = rs.getBigDecimal("on_hand");
                    BigDecimal reorder = rs.getBigDecimal("reorder_level");
                    boolean critical = reorder.signum() > 0
                            && onHand.divide(reorder, 4, RoundingMode.HALF_UP)
                                     .compareTo(new BigDecimal("0.45")) < 0;
                    return new LowStockRow(rs.getString("description"),
                            rs.getString("bin_code"), onHand, reorder, critical);
                })
                .list();
    }

    // ---- Chart ---------------------------------------------------------------

    private static final String MOVEMENT_BY_DAY = """
            SELECT d.day::date AS day,
                   COALESCE(SUM(m.quantity_base_uom) FILTER (WHERE m.direction = 'IN'), 0)  AS received,
                   COALESCE(SUM(m.quantity_base_uom) FILTER (WHERE m.direction = 'OUT'), 0) AS dispatched
              FROM generate_series(:from::date, :to::date, INTERVAL '1 day') AS d(day)
         LEFT JOIN stock_movement m
                ON m.business_date = d.day::date AND m.branch_id = :branchId
               AND count_freezing(m.item_id, m.location_id) IS NULL
             GROUP BY d.day
             ORDER BY d.day
            """;

    public ChartSeries movementSeries(UUID branchId, int days, String subtitle) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(days - 1L);

        List<String> labels = new ArrayList<>();
        List<Long> received = new ArrayList<>();
        List<Long> dispatched = new ArrayList<>();

        jdbc.sql(MOVEMENT_BY_DAY)
                .param("branchId", branchId).param("from", from).param("to", to)
                .query((rs, n) -> {
                    labels.add(rs.getObject("day", LocalDate.class)
                            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM")));
                    received.add(rs.getBigDecimal("received").longValue());
                    dispatched.add(rs.getBigDecimal("dispatched").longValue());
                    return null;
                })
                .list();

        return new ChartSeries(subtitle, labels, received, dispatched);
    }
}
