package heritier.ntaganira.highbytes.wms.masterdata.supplier;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.supplier
 * - File       : SupplierRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of a supplier for the list and the view
 * </pre>
 */

import java.util.UUID;

public record SupplierRow(
        UUID id,
        String code,
        String name,
        String tin,
        String countryCode,
        boolean foreign,
        String address,
        String phone,
        String email,
        boolean active,
        int receiptCount
) {}
