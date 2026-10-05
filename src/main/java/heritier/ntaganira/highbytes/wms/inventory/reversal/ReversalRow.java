package heritier.ntaganira.highbytes.wms.inventory.reversal;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.reversal
 * - File       : ReversalRow.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One reversing document on the list
 * </pre>
 */

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of the list. {@code awaitingRole} names who must sign next while
 * the reversal is pending; {@code awaitingMe} is true when that is the
 * signed-in user's signature to give.
 */
public record ReversalRow(
        UUID id,
        String serialNo,
        String status,
        LocalDate documentDate,
        String originalSerial,
        String originalTitle,
        int movementCount,
        BigDecimal valueRwf,
        String createdByName,
        String awaitingRole,
        boolean awaitingMe
) {

    public ReversalRow markAwaitingMe(boolean mine) {
        return new ReversalRow(id, serialNo, status, documentDate, originalSerial, originalTitle, movementCount,
                valueRwf, createdByName, awaitingRole, mine);
    }
}
