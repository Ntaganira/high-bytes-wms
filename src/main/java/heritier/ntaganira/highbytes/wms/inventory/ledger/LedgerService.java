package heritier.ntaganira.highbytes.wms.inventory.ledger;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.ledger
 * - File       : LedgerService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Writes stock movements and keeps stock_balance and the moving average cost in step
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The only writer of stock. Receipts, and later dispatches, transfers and
 * count adjustments, call {@link #post}; nothing else inserts into
 * {@code stock_movement}.
 *
 * <p>For each movement, in the caller's transaction:
 * <ol>
 *   <li>takes a transaction-scoped advisory lock on (item, location), so two
 *       movements of the same item at the same place queue and each sees the
 *       other's running balance;</li>
 *   <li>creates the {@code stock_balance} row if the place has none
 *       ({@code ON CONFLICT DO NOTHING} on the one-row-per-place constraint,
 *       which treats a NULL bin as one place) and locks it;</li>
 *   <li>works out the value, the moving weighted-average cost and the running
 *       balance at the location;</li>
 *   <li>inserts the ledger row, dated by the database's Kigali business date,
 *       so a locked day refuses it at the ledger; and</li>
 *   <li>updates the balance.</li>
 * </ol>
 * If the ledger refuses (a locked day, a movement that differs from its ticket
 * line, a document not fully approved), the refusal's own reason is thrown as a
 * {@link ControlRefusedException} and the whole transaction rolls back, balance
 * included.
 *
 * <p>Cost: a receipt brings in the value it is given; the average becomes
 * total value over quantity on hand. An issue leaves at that average, so the
 * remaining stock keeps its unit cost, and an issue that empties the place
 * takes exactly what value is left, so no rounding residue lingers. Stock
 * never goes below zero: an issue that would is refused. The average is held
 * per bin when stock is binned.
 *
 * <p>The issue path has no caller yet; it is here so dispatch has one ledger
 * to call and is exercised when that module lands.
 */
@Service
public class LedgerService {

    private final JdbcClient jdbc;

    public LedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An (item, location) a posting is about to move. */
    public record Place(UUID itemId, UUID locationId) {
        String key() { return itemId + ":" + locationId; }
    }

    /**
     * Takes the advisory lock of every place a posting will touch, all at
     * once and in one fixed order (item, then location), before any row is
     * written. Two postings touching the same items in opposite line order
     * then queue instead of each holding one lock and waiting for the
     * other's, which PostgreSQL would break by aborting one with a deadlock.
     * {@link #post} takes the same locks again, which the transaction already
     * holds.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPlaces(java.util.Collection<Place> places) {
        var keys = places.stream().map(Place::key).distinct().sorted().toList();
        try {
            for (String key : keys) {
                jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                        .param("key", key).query((rs, n) -> 1).single();
            }
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry post(MovementRequest m, UUID postedBy) {
        BigDecimal quantity = m.quantityBase();
        if (quantity == null || quantity.signum() <= 0) {
            throw new ControlRefusedException("A stock movement carries a positive quantity in the item's base unit.");
        }
        boolean in = m.direction() == MovementRequest.Direction.IN;
        if (in && (m.value() == null || m.value().signum() < 0)) {
            throw new ControlRefusedException("A receipt into stock carries its value, which cannot be negative.");
        }

        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", m.itemId() + ":" + m.locationId())
                .query((rs, n) -> 1).single();

        jdbc.sql("""
                INSERT INTO stock_balance (item_id, location_id, storage_bin_id)
                VALUES (:item, :location, :bin)
                ON CONFLICT ON CONSTRAINT stock_balance_one_row_per_place DO NOTHING
                """)
                .param("item", m.itemId(), Types.OTHER)
                .param("location", m.locationId(), Types.OTHER)
                .param("bin", m.storageBinId(), Types.OTHER)
                .update();

        record Balance(UUID id, BigDecimal quantity, BigDecimal totalValue) {}
        Balance row = jdbc.sql("""
                SELECT id, qty_on_hand, total_value FROM stock_balance
                 WHERE item_id = :item AND location_id = :location
                   AND storage_bin_id IS NOT DISTINCT FROM :bin::uuid
                   FOR UPDATE
                """)
                .param("item", m.itemId(), Types.OTHER)
                .param("location", m.locationId(), Types.OTHER)
                .param("bin", m.storageBinId(), Types.OTHER)
                .query((rs, n) -> new Balance(rs.getObject("id", UUID.class),
                        rs.getBigDecimal("qty_on_hand"), rs.getBigDecimal("total_value")))
                .single();

        BigDecimal value;
        BigDecimal newQuantity;
        BigDecimal newTotal;
        if (in) {
            value = m.value().setScale(2, RoundingMode.HALF_UP);
            newQuantity = row.quantity().add(quantity);
            newTotal = row.totalValue().add(value);
        } else {
            if (row.quantity().compareTo(quantity) < 0) {
                throw new ControlRefusedException("Only " + row.quantity().stripTrailingZeros().toPlainString()
                        + " is on hand there, so " + quantity.stripTrailingZeros().toPlainString()
                        + " cannot leave. Stock never goes below zero.");
            }
            newQuantity = row.quantity().subtract(quantity);
            value = newQuantity.signum() == 0
                    ? row.totalValue()
                    : row.totalValue().multiply(quantity).divide(row.quantity(), 2, RoundingMode.HALF_UP);
            newTotal = row.totalValue().subtract(value);
        }
        BigDecimal unitCost = value.divide(quantity, 4, RoundingMode.HALF_UP);
        BigDecimal average = newQuantity.signum() > 0
                ? newTotal.divide(newQuantity, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4);

        // The running balance is the stock card's: everything of this item at
        // this location, whatever the bin.
        BigDecimal elsewhere = jdbc.sql("""
                SELECT COALESCE(SUM(qty_on_hand), 0) FROM stock_balance
                 WHERE item_id = :item AND location_id = :location AND id <> :row
                """)
                .param("item", m.itemId(), Types.OTHER)
                .param("location", m.locationId(), Types.OTHER)
                .param("row", row.id(), Types.OTHER)
                .query(BigDecimal.class).single();
        BigDecimal running = elsewhere.add(newQuantity);

        record Written(long id, LocalDate businessDate) {}
        Written written;
        try {
            written = jdbc.sql("""
                    INSERT INTO stock_movement (ticket_line_id, document_id, branch_id, item_id, location_id,
                                                storage_bin_id, direction, quantity_base_uom, signed_quantity,
                                                unit_cost, value, running_balance, business_date, posted_by)
                    VALUES (:line, :ticket, :branch, :item, :location, :bin, :direction, :quantity, :signed,
                            :unitCost, :value, :running, kigali_today(), :postedBy)
                    RETURNING id, business_date
                    """)
                    .param("line", m.ticketLineId(), Types.OTHER)
                    .param("ticket", m.ticketId(), Types.OTHER)
                    .param("branch", m.branchId(), Types.OTHER)
                    .param("item", m.itemId(), Types.OTHER)
                    .param("location", m.locationId(), Types.OTHER)
                    .param("bin", m.storageBinId(), Types.OTHER)
                    .param("direction", m.direction().name())
                    .param("quantity", quantity)
                    .param("signed", in ? quantity : quantity.negate())
                    .param("unitCost", unitCost)
                    .param("value", value)
                    .param("running", running)
                    .param("postedBy", postedBy, Types.OTHER)
                    .query((rs, n) -> new Written(rs.getLong("id"), rs.getObject("business_date", LocalDate.class)))
                    .single();
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }

        jdbc.sql("""
                UPDATE stock_balance
                   SET qty_on_hand = :quantity, total_value = :total, average_unit_cost = :average,
                       last_movement_at = now()
                 WHERE id = :id
                """)
                .param("id", row.id(), Types.OTHER)
                .param("quantity", newQuantity)
                .param("total", newTotal)
                .param("average", average)
                .update();

        return new LedgerEntry(written.id(), unitCost, value, running, written.businessDate());
    }
}
