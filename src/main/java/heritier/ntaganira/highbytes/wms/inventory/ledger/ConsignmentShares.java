package heritier.ntaganira.highbytes.wms.inventory.ledger;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.ledger
 * - File       : ConsignmentShares.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The one rule that prices stock leaving transit or coming back from a customer, read from the ledger
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.util.UUID;

/**
 * What a movement of one consignment is worth, worked out from the ledger and
 * from nothing else.
 *
 * <p><strong>Out of transit</strong> (a receipt's leg or a transit loss, whichever
 * comes first). Per transfer line, every movement that takes stock out of
 * transit, in ledger order, carries
 * {@code share_k = round(v x cumQ_k / Q, 2) - round(v x cumQ_(k-1) / Q, 2)}, half up,
 * where v is the value the dispatch put into transit, Q the dispatched base
 * quantity and cumQ the running total of what was taken out. Everything that
 * leaves, received or lost, therefore adds up to exactly v: no cent is stranded
 * in transit and none is over-taken, however a line is split across receipts and
 * losses and in whatever order they post.
 *
 * <p>The running total starts from what the ledger already holds for that line
 * (earlier receipts, earlier losses, earlier lines of the posting in progress,
 * which are in the ledger by the time the next one is priced), never from a
 * count kept in memory. That is what lets a receipt and a loss share one rule,
 * and it is the database's own: {@code transit_out_shares} recomputes it at
 * commit and refuses a posting that differs.
 *
 * <p><strong>Back from a customer</strong>: the same, per delivery note line,
 * against what the delivery took out ({@code return_shares}).
 */
@Service
public class ConsignmentShares {

    private final JdbcClient jdbc;

    public ConsignmentShares(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What leaves transit when {@code quantityBase} more of this transfer line does, valued at its own dispatch cost. */
    public BigDecimal transitShare(UUID transferLineId, BigDecimal quantityBase) {
        record Dispatched(BigDecimal value, BigDecimal quantity) {}
        Dispatched d = jdbc.sql("""
                SELECT m.value AS v, tl.qty_base_uom AS q_total
                  FROM transfer_order_line tl
                  JOIN transaction_ticket t ON t.source_document_id = tl.document_id AND t.movement_type = 'TRANSFER_IN'
                  JOIN ticket_line x        ON x.ticket_id = t.document_id AND x.line_no = tl.line_no
                  JOIN stock_movement m     ON m.ticket_line_id = x.id AND m.reverses_movement_id IS NULL
                 WHERE tl.id = :line
                """)
                .param("line", transferLineId, Types.OTHER)
                .query((rs, n) -> new Dispatched(rs.getBigDecimal("v"), rs.getBigDecimal("q_total")))
                .optional()
                .orElseThrow(() -> new ControlRefusedException(
                        "That line of the transfer has no dispatch in the ledger, so nothing of it can leave transit."));
        BigDecimal before = jdbc.sql("SELECT COALESCE(SUM(qty), 0) FROM transit_out_shares(:line)")
                .param("line", transferLineId, Types.OTHER)
                .query(BigDecimal.class).single();
        return share(d.value(), d.quantity(), before, quantityBase);
    }

    /** What comes back when {@code quantityBase} more of this delivery note line does, valued at the cost it left with. */
    public BigDecimal returnShare(UUID deliveryNoteLineId, BigDecimal quantityBase) {
        record Delivered(BigDecimal value, BigDecimal quantity) {}
        Delivered d = jdbc.sql("""
                SELECT mo.value AS v, nl.qty_base_uom AS q_total
                  FROM delivery_note_line nl
                  JOIN transaction_ticket t ON t.source_document_id = nl.document_id AND t.movement_type = 'DELIVERY'
                  JOIN ticket_line l        ON l.ticket_id = t.document_id AND l.line_no = nl.line_no
                  JOIN stock_movement mo    ON mo.ticket_line_id = l.id AND mo.reverses_movement_id IS NULL
                 WHERE nl.id = :line
                """)
                .param("line", deliveryNoteLineId, Types.OTHER)
                .query((rs, n) -> new Delivered(rs.getBigDecimal("v"), rs.getBigDecimal("q_total")))
                .optional()
                .orElseThrow(() -> new ControlRefusedException(
                        "That line of the delivery note has no delivery in the ledger, so nothing of it can come back."));
        BigDecimal before = jdbc.sql("SELECT COALESCE(SUM(qty), 0) FROM return_shares(:line)")
                .param("line", deliveryNoteLineId, Types.OTHER)
                .query(BigDecimal.class).single();
        return share(d.value(), d.quantity(), before, quantityBase);
    }

    /** {@code round(v x (before + q) / Q, 2) - round(v x before / Q, 2)}, half up. Pure, so it is tested alone. */
    public static BigDecimal share(BigDecimal value, BigDecimal total, BigDecimal before, BigDecimal quantity) {
        BigDecimal cumulative = before.add(quantity);
        return value.multiply(cumulative).divide(total, 2, RoundingMode.HALF_UP)
                .subtract(value.multiply(before).divide(total, 2, RoundingMode.HALF_UP));
    }
}
