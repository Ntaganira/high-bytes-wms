package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DnHeader.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one delivery note's header, for the view and the form
 * </pre>
 */

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A delivery note's header as the screens read it, with the authorization it
 * delivers already joined. The getters at the bottom exist because
 * {@code fragments/ui :: documentHeader} reads bean properties.
 */
public record DnHeader(
        UUID id,
        UUID branchId,
        String branchName,
        boolean branchBonded,
        String serialNo,
        String status,
        int version,
        UUID createdBy,
        String createdByName,
        LocalDateTime createdAt,
        LocalDateTime postedAt,
        UUID postedBy,
        String postedByName,
        LocalDateTime cancelledAt,
        String cancelledByName,
        String cancelReason,
        UUID authorizationId,
        String daoSerial,
        String daoStatus,
        UUID daoCreatedBy,
        String daoCreatedByName,
        String customerName,
        UUID locationId,
        String locationCode,
        String locationName,
        boolean locationBonded,
        String customsReference,
        String vehicleRegistration,
        String driverName,
        String driverPhone,
        String driverIdNo
) {

    public boolean bonded() {
        return branchBonded || locationBonded;
    }

    public String getSerialNo()         { return serialNo; }
    public String getStatus()           { return status; }
    public boolean isBonded()           { return bonded(); }
    public String getBranchName()       { return branchName; }
    public String getCreatedByName()    { return createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
