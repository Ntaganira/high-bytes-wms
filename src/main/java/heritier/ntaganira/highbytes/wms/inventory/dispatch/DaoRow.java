package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DaoRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one row of the delivery authorization list
 * </pre>
 */

import java.time.LocalDate;
import java.util.UUID;

/** {@code displayState}: DRAFT, PENDING, RELEASED, DELIVERED, REJECTED or CANCELLED. */
public record DaoRow(
        UUID id,
        String serialNo,
        String displayState,
        LocalDate documentDate,
        String customerName,
        String locationCode,
        boolean bonded,
        int lineCount,
        String createdByName,
        String awaitingRole,
        boolean awaitingMe
) {

    public DaoRow markAwaitingMe(boolean mine) {
        return new DaoRow(id, serialNo, displayState, documentDate, customerName, locationCode, bonded,
                lineCount, createdByName, awaitingRole, mine);
    }
}
