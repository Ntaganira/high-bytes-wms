package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : CountHeader.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A count's header with its location, and where it is in its blind phases
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The count's header. {@code bookVisible} is the database's verdict
 * ({@code count_book_visible}): until the verification count is signed nobody
 * reads the book, and the screens built from this header leave it out entirely.
 * The getters exist because {@code fragments/ui :: documentHeader} reads bean
 * properties.
 */
public record CountHeader(
        UUID id,
        UUID branchId,
        String branchName,
        boolean branchBonded,
        String serialNo,
        String status,
        LocalDate documentDate,
        String reference,
        String notes,
        int version,
        UUID createdBy,
        String createdByName,
        LocalDateTime createdAt,
        LocalDateTime submittedAt,
        LocalDateTime postedAt,
        UUID postedBy,
        String postedByName,
        LocalDateTime cancelledAt,
        String cancelledByName,
        String cancelReason,
        UUID locationId,
        String locationCode,
        String locationName,
        boolean locationBonded,
        CountScope scope,
        String customsReference,
        boolean verificationSigned,
        boolean bookVisible
) {

    public boolean bonded() {
        return branchBonded || locationBonded;
    }

    /** Counting: the first count is open, blind. */
    public boolean counting() {
        return "DRAFT".equals(status);
    }

    /** Submitted and awaiting its verification count, blind to the book and the first count. */
    public boolean awaitingVerification() {
        return "PENDING".equals(status) && !verificationSigned;
    }

    /** While counting or awaiting verification the ledger refuses what the count covers. */
    public boolean frozen() {
        return counting() || awaitingVerification();
    }

    public String getSerialNo()         { return serialNo; }
    public String getStatus()           { return status; }
    public boolean isBonded()           { return bonded(); }
    public String getBranchName()       { return branchName; }
    public String getCreatedByName()    { return createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
