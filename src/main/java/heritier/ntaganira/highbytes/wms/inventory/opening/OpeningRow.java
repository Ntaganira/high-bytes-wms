package heritier.ntaganira.highbytes.wms.inventory.opening;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.opening
 * - File       : OpeningRow.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One line of the opening balance register
 * </pre>
 */

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of the list. {@code awaitingRole} names who must sign next while
 * the sheet is pending; {@code awaitingMe} is true when that is the signed-in
 * user's signature to give.
 */
public record OpeningRow(
        UUID id,
        String serialNo,
        String status,
        LocalDate documentDate,
        LocalDate asAtDate,
        String locationCode,
        boolean bonded,
        int lineCount,
        BigDecimal valueRwf,
        String createdByName,
        String awaitingRole,
        boolean awaitingMe
) {

    public OpeningRow markAwaitingMe(boolean mine) {
        return new OpeningRow(id, serialNo, status, documentDate, asAtDate, locationCode, bonded,
                lineCount, valueRwf, createdByName, awaitingRole, mine);
    }
}
