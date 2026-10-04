package heritier.ntaganira.highbytes.wms.inventory.opening;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.opening
 * - File       : OpeningLineRow.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One line of an opening stock balance, as the screens read it
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One item at its cutover quantity and carrying cost, with the item, unit and
 * bin names already joined.
 */
public record OpeningLineRow(
        UUID id,
        int lineNo,
        UUID itemId,
        String itemCode,
        String description,
        String productType,
        UUID uomId,
        String uomCode,
        BigDecimal quantity,
        BigDecimal quantityBase,
        String baseUomCode,
        BigDecimal unitCost,
        UUID storageBinId,
        String binCode,
        BigDecimal measuredThicknessMm,
        String note
) {

    /** What this line carries into the ledger: the base quantity at its carrying cost. */
    public BigDecimal valueRwf() {
        return quantityBase.multiply(unitCost).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public boolean glass() {
        return "GLASS".equals(productType);
    }
}
