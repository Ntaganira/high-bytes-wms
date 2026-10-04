package heritier.ntaganira.highbytes.wms.inventory.opening;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.opening
 * - File       : OpeningHeader.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : An opening stock balance's header as the screens read it
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The spine columns and the opening balance's own, with the names already
 * joined.
 *
 * <p>{@code documentDate} is the day the sheet was keyed; {@code asAtDate} is
 * the day the figures were struck in the old books. They are usually
 * different, and the pair is what an auditor reads first.
 *
 * <p>The getters at the bottom exist because {@code fragments/ui ::
 * documentHeader} reads bean properties.
 */
public record OpeningHeader(
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
        LocalDateTime approvedAt,
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
        LocalDate asAtDate,
        String sourceSystem,
        String basisNote,
        String customsReference,
        boolean isPosted
) {

    public UUID getId()                   { return id; }
    public String getSerialNo()            { return serialNo; }
    public String getStatus()              { return status; }
    public LocalDate getDocumentDate()     { return documentDate; }
    public String getBranchName()          { return branchName; }
    public String getReference()           { return reference; }
    public String getNotes()               { return notes; }
    public String getCreatedByName()       { return createdByName; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
    public LocalDateTime getSubmittedAt()  { return submittedAt; }
    public LocalDateTime getApprovedAt()   { return approvedAt; }
    public LocalDateTime getPostedAt()     { return postedAt; }
    public String getPostedByName()        { return postedByName; }
    public LocalDateTime getCancelledAt()  { return cancelledAt; }
    public String getCancelledByName()     { return cancelledByName; }
    public String getCancelReason()        { return cancelReason; }

    /** Whether the place is bonded, so the customs reference is shown and required. */
    public boolean bonded() {
        return locationBonded || branchBonded;
    }
}
