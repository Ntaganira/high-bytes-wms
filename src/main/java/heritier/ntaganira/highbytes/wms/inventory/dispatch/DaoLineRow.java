package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DaoLineRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one line of a delivery authorization
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

public record DaoLineRow(
        UUID id,
        int lineNo,
        UUID itemId,
        String itemCode,
        String itemDescription,
        String productType,
        BigDecimal nominalThicknessMm,
        UUID uomId,
        String uomCode,
        String baseUomCode,
        BigDecimal quantity,
        BigDecimal quantityBase,
        String note
) {

    public boolean glass() {
        return "GLASS".equals(productType);
    }
}
