package heritier.ntaganira.highbytes.wms.inventory.cutting;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.cutting
 * - File       : CuttingRow.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One cutting order in the list at a branch
 * </pre>
 */

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A row of the cutting order list. {@code awaitingMe} is set after reading, from the engine's own judgement. */
public record CuttingRow(UUID id, String serialNo, String displayState, LocalDate documentDate, String customerName,
                         String locationCode, String sheetCode, BigDecimal sheets, int pieces, int offcuts,
                         String createdByName, String awaitingRole, boolean awaitingMe) {

    public CuttingRow markAwaitingMe(boolean mine) {
        return new CuttingRow(id, serialNo, displayState, documentDate, customerName, locationCode, sheetCode, sheets,
                pieces, offcuts, createdByName, awaitingRole, mine);
    }
}
