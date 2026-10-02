package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : DamageLookupService.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Pickers, stock figures and the two worklists the return and damage screens need
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.BinOption;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.ItemOption;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.LocationOption;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.UnitOption;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What the report forms let a user choose from, how much stock there is, and the
 * two worklists that lead into a report: stock left in transit at the source
 * branch (to write off), and stock sitting in quarantine (to release or write off).
 *
 * <p>Every method reads for one branch, the one the user is working in; the
 * controller passes the current branch, and a record at another branch is refused
 * where it is opened, not here.
 */
@Service
@Transactional(readOnly = true)
public class DamageLookupService {

    /** A dispatched transfer with stock still in the source branch's transit location. */
    public record TransferPick(UUID id, String serialNo, String toBranch, String toCode, int lines,
                               LocalDateTime dispatchedAt) {}

    /** A posted delivery note with goods that could still come back. */
    public record NotePick(UUID id, String serialNo, String customer, LocalDateTime postedAt, int lines) {}

    /**
     * A transfer line a loss may be written against. {@code remaining} is what is still in transit in the
     * line's own unit, {@code remainingBase} in the base unit.
     */
    public record LossLine(UUID id, int lineNo, UUID itemId, String itemCode, String itemDescription, UUID uomId,
                           String uomCode, String baseUomCode, BigDecimal dispatched, BigDecimal remaining,
                           BigDecimal remainingBase, BigDecimal receivedBase, BigDecimal writtenOffBase) {}

    /** A delivery note line goods may come back against. */
    public record ReturnLine(UUID id, int lineNo, UUID itemId, String itemCode, String itemDescription, UUID uomId,
                             String uomCode, String baseUomCode, BigDecimal delivered, BigDecimal returnable,
                             BigDecimal returnableBase, BigDecimal returnedBase) {}

    /** Stock at a location: per item and bin, with what it is worth. */
    public record StockRow(UUID itemId, String itemCode, String itemDescription, String baseUomCode, UUID binId,
                           String binCode, BigDecimal quantity, BigDecimal value, LocalDateTime lastMovementAt) {
        public String place() { return binCode == null ? "no bin" : "bin " + binCode; }
    }

    /** One transfer line whose stock is still in transit, for the source branch's worklist. */
    public record InTransitRow(UUID transferId, String transferSerial, String state, String toBranch, int lineNo,
                               String itemCode, String itemDescription, String baseUomCode, BigDecimal dispatched,
                               BigDecimal received, BigDecimal writtenOff, BigDecimal inTransit,
                               LocalDateTime dispatchedAt, long daysInTransit) {}

    /** Stock in a quarantine location, for the worklist. */
    public record QuarantineRow(UUID locationId, String locationCode, UUID itemId, String itemCode,
                                String itemDescription, String baseUomCode, String binCode, BigDecimal quantity,
                                BigDecimal value, LocalDateTime lastMovementAt) {}

    private final JdbcClient jdbc;

    public DamageLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- pickers -------------------------------------------------------------

    /** Where stock may be written off from: a warehouse, bonded or quarantine location at the branch. */
    @PreAuthorize("hasAuthority('damage.create')")
    public List<LocationOption> writeOffLocations(UUID branchId) {
        return locations(branchId, "l.location_type IN ('WAREHOUSE', 'BONDED', 'QUARANTINE')");
    }

    /** The quarantine locations of the branch: where a release takes stock from. */
    @PreAuthorize("hasAuthority('damage.view')")
    public List<LocationOption> quarantineLocations(UUID branchId) {
        return locations(branchId, "l.location_type = 'QUARANTINE'");
    }

    /** Where released stock may go: a sellable warehouse or bonded location at the branch. */
    @PreAuthorize("hasAuthority('damage.create')")
    public List<LocationOption> sellableLocations(UUID branchId) {
        return locations(branchId, "l.location_type IN ('WAREHOUSE', 'BONDED') AND l.is_sellable");
    }

    private List<LocationOption> locations(UUID branchId, String condition) {
        return jdbc.sql("SELECT l.id, l.code, l.name, l.is_bonded FROM location l "
                        + " WHERE l.branch_id = :branch AND l.is_active AND " + condition + " ORDER BY l.code")
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new LocationOption(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getBoolean("is_bonded")))
                .list();
    }

    @PreAuthorize("hasAuthority('damage.create')")
    public List<ItemOption> items() {
        return jdbc.sql("""
                SELECT i.id, i.item_code, i.description, i.product_type, u.code AS base_uom
                  FROM item i JOIN uom u ON u.id = i.base_uom_id
                 WHERE i.is_active ORDER BY i.item_code
                """)
                .query((rs, n) -> new ItemOption(rs.getObject("id", UUID.class), rs.getString("item_code"),
                        rs.getString("description"), rs.getString("product_type"), rs.getString("base_uom")))
                .list();
    }

    @PreAuthorize("hasAuthority('damage.create')")
    public List<UnitOption> units() {
        return jdbc.sql("SELECT id, code, name FROM uom WHERE is_active ORDER BY code")
                .query((rs, n) -> new UnitOption(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getString("name")))
                .list();
    }

    /**
     * The active bins of a location at this branch. The location id comes from the
     * posted form, so one elsewhere yields nothing: a right held here reads nothing there.
     */
    @PreAuthorize("hasAuthority('damage.view')")
    public List<BinOption> binsOf(UUID locationId, UUID branchId) {
        if (locationId == null) return List.of();
        return jdbc.sql("""
                SELECT b.id, b.location_id, b.bin_code, b.zone FROM storage_bin b
                  JOIN location l ON l.id = b.location_id
                 WHERE b.location_id = :location AND l.branch_id = :branch AND b.is_active
                 ORDER BY b.bin_code
                """)
                .param("location", locationId, Types.OTHER)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new BinOption(rs.getObject("id", UUID.class),
                        rs.getObject("location_id", UUID.class), rs.getString("bin_code"), rs.getString("zone")))
                .list();
    }

    /** Whether a location, or the branch it is at, is bonded: the customs reference is then required. */
    @PreAuthorize("hasAuthority('damage.view')")
    public boolean touchesBonded(UUID branchId, UUID... locationIds) {
        boolean branchBonded = jdbc.sql("SELECT is_bonded FROM branch WHERE id = :id")
                .param("id", branchId, Types.OTHER).query(Boolean.class).optional().orElse(false);
        if (branchBonded) return true;
        for (UUID id : locationIds) {
            if (id == null) continue;
            boolean bonded = jdbc.sql("SELECT is_bonded FROM location WHERE id = :id")
                    .param("id", id, Types.OTHER).query(Boolean.class).optional().orElse(false);
            if (bonded) return true;
        }
        return false;
    }

    /**
     * What is at this location, by item and bin, valued: the figures a write-off or a
     * release is weighed against. Only for a location at this branch, as {@link #binsOf}.
     */
    @PreAuthorize("hasAuthority('damage.view')")
    public List<StockRow> stockAt(UUID locationId, UUID branchId) {
        if (locationId == null) return List.of();
        return jdbc.sql("""
                SELECT sb.item_id, i.item_code, i.description, bu.code AS base_uom, sb.storage_bin_id, b.bin_code,
                       sb.qty_on_hand, sb.total_value, sb.last_movement_at
                  FROM stock_balance sb
                  JOIN location l ON l.id = sb.location_id
                  JOIN item i ON i.id = sb.item_id
                  JOIN uom bu ON bu.id = i.base_uom_id
             LEFT JOIN storage_bin b ON b.id = sb.storage_bin_id
                 WHERE sb.location_id = :location AND l.branch_id = :branch AND sb.qty_on_hand > 0
                   -- A place under a live count shows no book, and nothing moves from it (V15).
                   AND count_freezing(sb.item_id, sb.location_id) IS NULL
                 ORDER BY i.item_code, b.bin_code NULLS FIRST
                """)
                .param("location", locationId, Types.OTHER)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new StockRow(rs.getObject("item_id", UUID.class), rs.getString("item_code"),
                        rs.getString("description"), rs.getString("base_uom"),
                        rs.getObject("storage_bin_id", UUID.class), rs.getString("bin_code"),
                        rs.getBigDecimal("qty_on_hand"), rs.getBigDecimal("total_value"),
                        KigaliTime.read(rs, "last_movement_at")))
                .list();
    }

    // ---- what a loss or a return is about ----------------------------------------

    /** Dispatched transfers from this branch that still have stock in transit: what a loss may be raised against. */
    @PreAuthorize("hasAuthority('damage.view')")
    public List<TransferPick> transfersWithRemainder(UUID branchId) {
        return jdbc.sql("""
                SELECT d.id, d.serial_no, tb.name AS to_branch, tl.code AS to_code, d.posted_at,
                       (SELECT COUNT(*) FROM transfer_line_position p WHERE p.transfer_id = d.id AND p.in_transit_base > 0) AS n
                  FROM document d
                  JOIN transfer_order t ON t.document_id = d.id
                  JOIN location tl      ON tl.id = t.to_location_id
                  JOIN branch tb        ON tb.id = tl.branch_id
                 WHERE d.branch_id = :branch AND d.status = 'POSTED'
                   AND EXISTS (SELECT 1 FROM transfer_line_position p WHERE p.transfer_id = d.id AND p.in_transit_base > 0)
                 ORDER BY d.posted_at
                """)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new TransferPick(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                        rs.getString("to_branch"), rs.getString("to_code"), rs.getInt("n"),
                        KigaliTime.read(rs, "posted_at")))
                .list();
    }

    /** The lines of a dispatched transfer with what is still in transit, in the line's own unit. */
    @PreAuthorize("hasAuthority('damage.view')")
    public List<LossLine> lossLines(UUID transferId) {
        return jdbc.sql("""
                SELECT l.id, l.line_no, l.item_id, i.item_code, i.description, l.uom_id, u.code AS uom_code,
                       bu.code AS base_uom, l.quantity, l.qty_base_uom,
                       p.in_transit_base, p.received_base, p.written_off_base
                  FROM transfer_order_line l
                  JOIN transfer_line_position p ON p.transfer_line_id = l.id
                  JOIN item i  ON i.id = l.item_id
                  JOIN uom u   ON u.id = l.uom_id
                  JOIN uom bu  ON bu.id = i.base_uom_id
                 WHERE l.document_id = :id
                 ORDER BY l.line_no
                """)
                .param("id", transferId, Types.OTHER)
                .query((rs, n) -> {
                    BigDecimal quantity = rs.getBigDecimal("quantity");
                    BigDecimal base = rs.getBigDecimal("qty_base_uom");
                    BigDecimal left = rs.getBigDecimal("in_transit_base");
                    return new LossLine(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                            rs.getObject("item_id", UUID.class), rs.getString("item_code"),
                            rs.getString("description"), rs.getObject("uom_id", UUID.class),
                            rs.getString("uom_code"), rs.getString("base_uom"), quantity,
                            inUnit(quantity, base, left), left,
                            rs.getBigDecimal("received_base"), rs.getBigDecimal("written_off_base"));
                })
                .list();
    }

    /** Posted delivery notes at this branch with goods that could still come back. */
    @PreAuthorize("hasAuthority('damage.view')")
    public List<NotePick> deliveryNotesWithReturnable(UUID branchId) {
        return jdbc.sql("""
                SELECT d.id, d.serial_no, c.name AS customer, d.posted_at,
                       (SELECT COUNT(*) FROM delivery_note_line l
                         WHERE l.document_id = d.id AND l.qty_base_uom > delivery_line_returned(l.id)) AS n
                  FROM document d
                  JOIN delivery_note n          ON n.document_id = d.id
                  JOIN delivery_authorization a ON a.document_id = n.authorization_id
                  JOIN customer c               ON c.id = a.customer_id
                 WHERE d.branch_id = :branch AND d.status = 'POSTED'
                   AND EXISTS (SELECT 1 FROM delivery_note_line l
                                WHERE l.document_id = d.id AND l.qty_base_uom > delivery_line_returned(l.id))
                 ORDER BY d.posted_at DESC
                 LIMIT 100
                """)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new NotePick(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                        rs.getString("customer"), KigaliTime.read(rs, "posted_at"), rs.getInt("n")))
                .list();
    }

    /** The lines of a posted delivery note with what could still come back, in the line's own unit. */
    @PreAuthorize("hasAuthority('damage.view')")
    public List<ReturnLine> returnLines(UUID noteId) {
        return jdbc.sql("""
                SELECT l.id, l.line_no, l.item_id, i.item_code, i.description, l.uom_id, u.code AS uom_code,
                       bu.code AS base_uom, l.quantity, l.qty_base_uom, delivery_line_returned(l.id) AS returned
                  FROM delivery_note_line l
                  JOIN item i  ON i.id = l.item_id
                  JOIN uom u   ON u.id = l.uom_id
                  JOIN uom bu  ON bu.id = i.base_uom_id
                 WHERE l.document_id = :id
                 ORDER BY l.line_no
                """)
                .param("id", noteId, Types.OTHER)
                .query((rs, n) -> {
                    BigDecimal quantity = rs.getBigDecimal("quantity");
                    BigDecimal base = rs.getBigDecimal("qty_base_uom");
                    BigDecimal returned = rs.getBigDecimal("returned");
                    BigDecimal left = base.subtract(returned);
                    return new ReturnLine(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                            rs.getObject("item_id", UUID.class), rs.getString("item_code"),
                            rs.getString("description"), rs.getObject("uom_id", UUID.class),
                            rs.getString("uom_code"), rs.getString("base_uom"), quantity,
                            inUnit(quantity, base, left), left, returned);
                })
                .list();
    }

    /** {@code baseAmount} expressed in the unit a line was entered in, from that line's own ratio. */
    static BigDecimal inUnit(BigDecimal quantity, BigDecimal quantityBase, BigDecimal baseAmount) {
        if (quantityBase == null || quantityBase.signum() == 0) return BigDecimal.ZERO;
        if (baseAmount.compareTo(quantityBase) >= 0) return quantity;
        return quantity.multiply(baseAmount).divide(quantityBase, 3, java.math.RoundingMode.HALF_UP);
    }

    // ---- worklists ------------------------------------------------------------------

    /**
     * Stock still in transit from this branch: a dispatched transfer whose line has not been received or
     * written off in full. A transfer no receipt has been posted against is DISPATCHED; one received short is IN_TRANSIT.
     */
    @PreAuthorize("hasAuthority('damage.view')")
    public List<InTransitRow> leftInTransit(UUID branchId) {
        return jdbc.sql("""
                SELECT d.id, d.serial_no, tb.name AS to_branch, l.line_no, i.item_code, i.description,
                       bu.code AS base_uom, p.dispatched_base, p.received_base, p.written_off_base, p.in_transit_base,
                       d.posted_at,
                       EXISTS (SELECT 1 FROM transfer_receipt r JOIN document rd ON rd.id = r.document_id
                                WHERE r.transfer_id = d.id AND rd.status = 'POSTED') AS received_some
                  FROM document d
                  JOIN transfer_order t         ON t.document_id = d.id
                  JOIN location tl              ON tl.id = t.to_location_id
                  JOIN branch tb                ON tb.id = tl.branch_id
                  JOIN transfer_line_position p ON p.transfer_id = d.id
                  JOIN transfer_order_line l    ON l.id = p.transfer_line_id
                  JOIN item i                   ON i.id = l.item_id
                  JOIN uom bu                   ON bu.id = i.base_uom_id
                 WHERE d.branch_id = :branch AND d.status = 'POSTED' AND p.in_transit_base > 0
                 ORDER BY d.posted_at, d.serial_no, l.line_no
                """)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> {
                    LocalDateTime at = KigaliTime.read(rs, "posted_at");
                    long days = at == null ? 0 : java.time.Duration.between(at,
                            LocalDateTime.now(KigaliTime.ZONE)).toDays();
                    return new InTransitRow(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                            rs.getBoolean("received_some") ? "IN_TRANSIT" : "DISPATCHED", rs.getString("to_branch"),
                            rs.getInt("line_no"), rs.getString("item_code"), rs.getString("description"),
                            rs.getString("base_uom"), rs.getBigDecimal("dispatched_base"),
                            rs.getBigDecimal("received_base"), rs.getBigDecimal("written_off_base"),
                            rs.getBigDecimal("in_transit_base"), at, days);
                })
                .list();
    }

    /** What sits in the branch's quarantine locations, waiting for a release or a write-off. */
    @PreAuthorize("hasAuthority('damage.view')")
    public List<QuarantineRow> quarantineStock(UUID branchId) {
        return jdbc.sql("""
                SELECT l.id AS location_id, l.code, sb.item_id, i.item_code, i.description, bu.code AS base_uom,
                       b.bin_code, sb.qty_on_hand, sb.total_value, sb.last_movement_at
                  FROM stock_balance sb
                  JOIN location l ON l.id = sb.location_id AND l.location_type = 'QUARANTINE'
                  JOIN item i     ON i.id = sb.item_id
                  JOIN uom bu     ON bu.id = i.base_uom_id
             LEFT JOIN storage_bin b ON b.id = sb.storage_bin_id
                 WHERE l.branch_id = :branch AND sb.qty_on_hand > 0
                   -- A place under a live count shows no book, and nothing moves from it (V15).
                   AND count_freezing(sb.item_id, sb.location_id) IS NULL
                 ORDER BY l.code, i.item_code, b.bin_code NULLS FIRST
                """)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new QuarantineRow(rs.getObject("location_id", UUID.class), rs.getString("code"),
                        rs.getObject("item_id", UUID.class), rs.getString("item_code"), rs.getString("description"),
                        rs.getString("base_uom"), rs.getString("bin_code"), rs.getBigDecimal("qty_on_hand"),
                        rs.getBigDecimal("total_value"), KigaliTime.read(rs, "last_movement_at")))
                .list();
    }
}
