package heritier.ntaganira.highbytes.wms.inventory.cutting;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.cutting
 * - File       : CuttingHeader.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A cutting order's header: the document, its customer and place, and where it stands
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The header of a cutting order.
 *
 * <p>{@code displayState} is what people read: an APPROVED order is RELEASED (to be cut and posted), a POSTED one
 * is POSTED until its pieces leave on a posted delivery note, then DELIVERED.
 *
 * <p>The getters exist because the shared document header fragment reads bean properties.
 */
public record CuttingHeader(
        UUID id,
        UUID branchId,
        String branchName,
        boolean branchBonded,
        String serialNo,
        String status,
        String displayState,
        LocalDate documentDate,
        String reference,
        String notes,
        int version,
        UUID createdBy,
        String createdByName,
        LocalDateTime createdAt,
        LocalDateTime submittedAt,
        LocalDateTime approvedAt,
        LocalDateTime postedAt,
        UUID postedBy,
        String postedByName,
        LocalDateTime cancelledAt,
        String cancelledByName,
        String cancelReason,
        UUID customerId,
        String customerName,
        boolean customerBlocked,
        UUID locationId,
        String locationCode,
        String locationName,
        boolean locationBonded,
        String customerReference,
        String customsReference
) {

    public boolean bonded() {
        return branchBonded || locationBonded;
    }

    public String getSerialNo()         { return serialNo; }
    /** The state people read, so the shared document header shows RELEASED or DELIVERED. */
    public String getStatus()           { return displayState; }
    public boolean isBonded()           { return bonded(); }
    public String getBranchName()       { return branchName; }
    public String getCreatedByName()    { return createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
