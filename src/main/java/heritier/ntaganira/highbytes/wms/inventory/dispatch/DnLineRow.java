package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DnLineRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one line of a delivery note
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

/** A loaded line: which authorization line it serves, what left and from which bin, and the thickness measured at the gate. */
public record DnLineRow(
        UUID id,
        int lineNo,
        UUID authorizationLineId,
        int daoLineNo,
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
        UUID storageBinId,
        String binCode,
        BigDecimal measuredThicknessMm,
        BigDecimal returnedBase
) {}
