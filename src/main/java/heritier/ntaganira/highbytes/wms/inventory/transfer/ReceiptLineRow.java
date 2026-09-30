package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : ReceiptLineRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one line of a transfer receipt
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

/** A received line: what arrived against what the transfer line dispatched. */
public record ReceiptLineRow(
        UUID id,
        int lineNo,
        UUID transferLineId,
        int transferLineNo,
        UUID itemId,
        String itemCode,
        String itemDescription,
        UUID uomId,
        String uomCode,
        String baseUomCode,
        BigDecimal quantity,
        BigDecimal quantityBase,
        BigDecimal dispatchedQuantity,
        UUID storageBinId,
        String binCode,
        String note
) {

    /** What was sent and did not arrive on this receipt, in the line's own unit. */
    public BigDecimal shortfall() {
        return dispatchedQuantity.subtract(quantity);
    }
}
