package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : TransferLineRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one transfer line, with what was dispatched, received and is still in transit
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A transfer line. The last three figures come from
 * {@code transfer_line_position} and are null until the transfer is
 * dispatched; they are in the item's base unit.
 */
public record TransferLineRow(
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
        String note,
        BigDecimal dispatchedBase,
        BigDecimal receivedBase,
        BigDecimal inTransitBase,
        BigDecimal writtenOffBase
) {

    public boolean dispatched() {
        return dispatchedBase != null;
    }

    /** Stock of this line still in the source branch's transit location. */
    public boolean leftInTransit() {
        return inTransitBase != null && inTransitBase.signum() > 0;
    }
}
