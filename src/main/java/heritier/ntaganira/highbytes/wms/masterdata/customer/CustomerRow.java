package heritier.ntaganira.highbytes.wms.masterdata.customer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.customer
 * - File       : CustomerRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of a customer for the list and the view
 * </pre>
 */

import java.math.BigDecimal;
import java.util.UUID;

public record CustomerRow(
        UUID id,
        String code,
        String name,
        String tin,
        CustomerMarket market,
        String address,
        String phone,
        String email,
        BigDecimal creditLimit,
        Integer paymentTermsDays,
        boolean blocked,
        boolean active,
        int authorizationCount
) {

    /** What the list shows beside the name: blocked outranks inactive, because it is the one that stops a delivery. */
    public String state() {
        if (!active) return "INACTIVE";
        return blocked ? "BLOCKED" : "ACTIVE";
    }
}
