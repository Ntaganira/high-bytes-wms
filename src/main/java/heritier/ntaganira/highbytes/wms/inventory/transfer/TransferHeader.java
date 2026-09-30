package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : TransferHeader.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one transfer's header, for the view and the form
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A transfer's header as the screens read it. A transfer belongs to its
 * SOURCE branch ({@code branchId}); the destination is the branch of
 * {@code toLocationId}.
 *
 * <p>{@code status} is the spine's; {@link #displayState()} is what people
 * read: a posted transfer is DISPATCHED until a receipt is posted, IN_TRANSIT
 * while part of it is still in the source branch's transit location after a
 * receipt, and RECEIVED once nothing is left there.
 */
public record TransferHeader(
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
        UUID supersedesId,
        String supersedesSerial,
        UUID fromLocationId,
        String fromCode,
        String fromName,
        boolean fromBonded,
        UUID toLocationId,
        String toCode,
        String toName,
        boolean toBonded,
        UUID toBranchId,
        String toBranchName,
        boolean toBranchBonded,
        UUID transitLocationId,
        String transitCode,
        String customsReference,
        String note
) {

    /** Bonded when either side, or either branch, is: the customs reference is then required. */
    public boolean bonded() {
        return branchBonded || fromBonded || toBonded || toBranchBonded;
    }

    public String getSerialNo()         { return serialNo; }
    /** The state people read, so the shared document header shows DISPATCHED, IN TRANSIT or RECEIVED. */
    public String getStatus()           { return displayState; }
    public boolean isBonded()           { return bonded(); }
    public String getBranchName()       { return branchName; }
    public String getCreatedByName()    { return createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
