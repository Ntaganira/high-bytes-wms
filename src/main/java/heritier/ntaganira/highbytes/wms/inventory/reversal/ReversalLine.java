package heritier.ntaganira.highbytes.wms.inventory.reversal;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.reversal
 * - File       : ReversalLine.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One movement of the original document, and its mirror: what a reversal undoes, line by line
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One movement the original made, as its reversal will undo it.
 *
 * <p>{@code direction} is the original's; the mirror goes the other way.
 * {@code mirrorId} is the reversing movement once posted. {@code problem} says
 * why the place cannot take the mirror today (stock or value no longer there,
 * a count under way), and is null when it can; it is worked out per place,
 * so two lines at one place are judged together.
 *
 * <p>{@code countedBy} is the count freezing the place, when there is one. The
 * book is not shown at a place under a live count (CLAUDE.md), so the service
 * leaves the problem unstated beyond naming the count.
 */
public record ReversalLine(
        long movementId,
        UUID ticketId,
        String ticketSerial,
        String movementType,
        UUID ticketLineId,
        int lineNo,
        UUID itemId,
        String itemCode,
        String description,
        String baseUomCode,
        UUID locationId,
        String locationCode,
        UUID storageBinId,
        String binCode,
        String direction,
        BigDecimal quantityBase,
        BigDecimal valueRwf,
        Long mirrorId,
        String countedBy,
        String problem
) {

    /** The direction the mirror moves: the other way. */
    public String mirrorDirection() {
        return "IN".equals(direction) ? "OUT" : "IN";
    }

    public ReversalLine withProblem(String why) {
        return new ReversalLine(movementId, ticketId, ticketSerial, movementType, ticketLineId, lineNo, itemId,
                itemCode, description, baseUomCode, locationId, locationCode, storageBinId, binCode, direction,
                quantityBase, valueRwf, mirrorId, countedBy, why);
    }
}
