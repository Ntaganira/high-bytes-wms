package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : ReadyRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of an authorization that is released and waiting to be loaded
 * </pre>
 */

import java.time.LocalDateTime;
import java.util.UUID;

/** A released authorization, or a posted cutting order, with no live delivery note: the gate's "ready to load" list. */
public record ReadyRow(
        UUID id,
        String serialNo,
        String customerName,
        String locationCode,
        boolean bonded,
        int lineCount,
        String releasedBy,
        LocalDateTime releasedAt,
        String kind
) {

    public boolean cuttingOrder() {
        return "CUT".equals(kind);
    }
}
