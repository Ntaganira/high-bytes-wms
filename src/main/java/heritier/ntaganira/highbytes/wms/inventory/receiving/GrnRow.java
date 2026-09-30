package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : GrnRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one row of the goods received list
 * </pre>
 */

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of the list. {@code awaitingRole} names who must sign next while
 * the note is pending; {@code awaitingMe} is true when that is the signed-in
 * user's signature to give.
 */
public record GrnRow(
        UUID id,
        String serialNo,
        String status,
        LocalDate documentDate,
        String supplierName,
        String locationCode,
        boolean bonded,
        int lineCount,
        BigDecimal invoiceRwf,
        String createdByName,
        String awaitingRole,
        boolean awaitingMe
) {

    public GrnRow markAwaitingMe(boolean mine) {
        return new GrnRow(id, serialNo, status, documentDate, supplierName, locationCode, bonded, lineCount,
                invoiceRwf, createdByName, awaitingRole, mine);
    }
}
