package heritier.ntaganira.highbytes.wms.masterdata.location;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.location
 * - File       : LocationRow.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A location as the screens read it, with its bin count and what it holds
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

/** A location as the screens read it, with its bin count and what it holds. */
public record LocationRow(
        UUID id,
        UUID branchId,
        String branchName,
        String code,
        String name,
        LocationType locationType,
        boolean bonded,
        boolean sellable,
        boolean active,
        int binCount,
        int distinctItems,
        BigDecimal totalValue
) {
    public boolean holdsStock() {
        return distinctItems > 0;
    }
}
