package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : GrnLineRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one line of a goods received note
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

public record GrnLineRow(
        UUID id,
        int lineNo,
        UUID itemId,
        String itemCode,
        String itemDescription,
        String productType,
        UUID uomId,
        String uomCode,
        String baseUomCode,
        BigDecimal quantity,
        BigDecimal quantityBase,
        BigDecimal unitPrice,
        UUID storageBinId,
        String binCode,
        BigDecimal measuredThicknessMm,
        BigDecimal supplierQuantityBase,
        String note
) {

    /** What the supplier's paperwork says arrived, less what was counted; positive is a shortage. */
    public BigDecimal shortfallBase() {
        return supplierQuantityBase == null ? null : supplierQuantityBase.subtract(quantityBase);
    }

    public boolean convertedUnit() {
        return !uomCode.equals(baseUomCode);
    }
}
