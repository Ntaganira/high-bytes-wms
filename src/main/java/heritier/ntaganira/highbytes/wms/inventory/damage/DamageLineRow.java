package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : DamageLineRow.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One line of a report as the view and the form read it
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A report line. {@code valueOut} and {@code valueIn} are the ledger's figures
 * once the report is posted (null before): what the stock was worth leaving, and
 * arriving.
 */
public record DamageLineRow(
        UUID id,
        int lineNo,
        UUID itemId,
        String itemCode,
        String itemDescription,
        UUID uomId,
        String uomCode,
        String baseUomCode,
        BigDecimal quantity,
        BigDecimal quantityBase,
        UUID storageBinId,
        String binCode,
        UUID toStorageBinId,
        String toBinCode,
        UUID transferLineId,
        Integer transferLineNo,
        UUID deliveryNoteLineId,
        Integer deliveryNoteLineNo,
        String note,
        BigDecimal valueOut,
        BigDecimal valueIn
) {

    /** What the line is worth in the ledger: the OUT value, or for a return the IN value. */
    public BigDecimal value() {
        return valueOut != null ? valueOut : valueIn;
    }
}
