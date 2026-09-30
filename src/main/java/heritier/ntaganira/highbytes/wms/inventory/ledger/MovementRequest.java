package heritier.ntaganira.highbytes.wms.inventory.ledger;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.ledger
 * - File       : MovementRequest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One stock movement a module asks the ledger to record
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One movement, exactly as its transaction-ticket line says.
 *
 * <p>{@code ticketId} is the TT document the movement is filed under and
 * {@code ticketLineId} the line it carries out; the ledger refuses a movement
 * that differs from its line, and one whose supporting document is not fully
 * approved. A receipt names the {@code value} it brings in (invoice plus
 * landed cost); an issue is valued at the moving weighted-average cost, so its
 * {@code value} is ignored.
 */
public record MovementRequest(
        UUID ticketId,
        UUID ticketLineId,
        UUID branchId,
        UUID itemId,
        UUID locationId,
        UUID storageBinId,
        Direction direction,
        BigDecimal quantityBase,
        BigDecimal value
) {

    public enum Direction { IN, OUT }

    public static MovementRequest receipt(UUID ticketId, UUID ticketLineId, UUID branchId, UUID itemId,
                                          UUID locationId, UUID storageBinId, BigDecimal quantityBase,
                                          BigDecimal value) {
        return new MovementRequest(ticketId, ticketLineId, branchId, itemId, locationId, storageBinId,
                Direction.IN, quantityBase, value);
    }

    public static MovementRequest issue(UUID ticketId, UUID ticketLineId, UUID branchId, UUID itemId,
                                        UUID locationId, UUID storageBinId, BigDecimal quantityBase) {
        return new MovementRequest(ticketId, ticketLineId, branchId, itemId, locationId, storageBinId,
                Direction.OUT, quantityBase, null);
    }
}
