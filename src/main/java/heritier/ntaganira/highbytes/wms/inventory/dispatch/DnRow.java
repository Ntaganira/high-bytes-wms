package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DnRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one row of the delivery note list
 * </pre>
 */

import java.time.LocalDateTime;
import java.util.UUID;

public record DnRow(
        UUID id,
        String serialNo,
        String status,
        UUID authorizationId,
        String daoSerial,
        String customerName,
        String vehicleRegistration,
        String driverName,
        String createdByName,
        LocalDateTime createdAt,
        LocalDateTime postedAt
) {}
