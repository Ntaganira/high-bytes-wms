package heritier.ntaganira.highbytes.wms.inventory.reversal;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.reversal
 * - File       : ReversalHeader.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A reversing document's spine columns, its reason, and the document it undoes
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The reversal and, beside it, who raised and posted the original: the two
 * people V21 keeps out of it, named on the page so nobody has to work out
 * why they cannot sign.
 *
 * <p>The getters at the bottom exist because {@code fragments/ui ::
 * documentHeader} reads bean properties.
 */
public record ReversalHeader(
        UUID id,
        UUID branchId,
        String branchName,
        boolean branchBonded,
        String serialNo,
        String status,
        LocalDate documentDate,
        int version,
        UUID createdBy,
        String createdByName,
        LocalDateTime createdAt,
        LocalDateTime postedAt,
        UUID postedBy,
        String postedByName,
        String cancelReason,
        String reason,
        UUID originalId,
        String originalSerial,
        String originalType,
        String originalTitle,
        String originalKind,
        LocalDateTime originalPostedAt,
        UUID originalCreatedBy,
        String originalCreatedByName,
        UUID originalPostedBy,
        String originalPostedByName
) {

    public UUID getId()                { return id; }
    public String getSerialNo()        { return serialNo; }
    public String getStatus()          { return status; }
    public String getBranchName()      { return branchName; }
    public String getCreatedByName()   { return createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public boolean bonded() {
        return branchBonded;
    }

    /** Where the original is read: its own module's page, through the spine's router. */
    public String originalPath() {
        return "/documents/" + originalId;
    }
}
