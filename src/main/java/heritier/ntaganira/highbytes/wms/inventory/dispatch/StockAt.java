package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : StockAt.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Stock on hand of one item in one place, shown before anyone tries to load it
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

/** On hand (base unit) of an item at a location, in one bin or, with no bin, unbinned. */
public record StockAt(UUID itemId, UUID binId, String binCode, BigDecimal quantity) {

    public String place() {
        return binCode == null ? "no bin" : "bin " + binCode;
    }
}
