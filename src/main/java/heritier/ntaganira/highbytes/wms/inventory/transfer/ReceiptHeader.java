package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : ReceiptHeader.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one transfer receipt's header, for the view and the form
 * </pre>
 */

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A transfer receipt's header. The receipt belongs to the DESTINATION branch
 * ({@code branchId}); {@code transferBranchId} is the source branch of the
 * transfer it receives. The getters serve {@code fragments/ui :: documentHeader}.
 */
public record ReceiptHeader(
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
        String note,
        UUID transferId,
        String transferSerial,
        String transferStatus,
        UUID transferBranchId,
        String transferBranchName,
        UUID dispatchedBy,
        String dispatchedByName,
        LocalDateTime dispatchedAt,
        UUID toLocationId,
        String toCode,
        String toName,
        String transitCode,
        String customsReference
) {

    public String getSerialNo()         { return serialNo; }
    public String getStatus()           { return status; }
    public boolean isBonded()           { return branchBonded; }
    public String getBranchName()       { return branchName; }
    public String getCreatedByName()    { return createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
