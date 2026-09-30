package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DaoHeader.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one delivery authorization's header, for the view and the form
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A delivery authorization's header as the screens read it.
 *
 * <p>{@code status} is the spine's ({@code APPROVED} once fully signed);
 * {@link #displayState()} is what people read: an approved authorization is
 * RELEASED, and DELIVERED once its delivery note is posted. The DAO is never
 * POSTED itself.
 */
public record DaoHeader(
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
        LocalDateTime cancelledAt,
        String cancelledByName,
        String cancelReason,
        UUID supersedesId,
        String supersedesSerial,
        UUID customerId,
        String customerName,
        boolean customerBlocked,
        UUID locationId,
        String locationCode,
        String locationName,
        boolean locationBonded,
        String customerReference,
        String deliveryAddress,
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
