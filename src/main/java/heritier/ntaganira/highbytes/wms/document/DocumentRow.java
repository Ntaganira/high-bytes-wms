package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : DocumentRow.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One document in the register of every document: what it is, where, and where it stands
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * A row of {@code /documents}. It says what a document is and where it stands, never what it carries: its own
 * page shows that, to whoever may read it there.
 *
 * @param step      for a PENDING document, the step that signs next (null otherwise)
 * @param openHere  whether the reader's rights where they are working reach the document's page; otherwise they
 *                  switch to its branch first
 */
public record DocumentRow(UUID id,
                          String serialNo,
                          String typeCode,
                          String typeName,
                          String status,
                          UUID branchId,
                          String branchCode,
                          String branchName,
                          LocalDate documentDate,
                          String reference,
                          String raisedBy,
                          LocalDateTime postedAt,
                          String postedBy,
                          String cancelReason,
                          Integer step,
                          int steps,
                          String awaitingRole,
                          boolean openHere) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /** Where the document stands, in a line. */
    public String standing() {
        return switch (status) {
            case "DRAFT" -> "Draft, not yet submitted";
            case "PENDING" -> step == null ? "Awaiting signatures"
                    : "Step " + step + " of " + steps + ", awaiting " + awaitingRole;
            case "APPROVED" -> switch (typeCode) {
                case "DAO" -> "Approved: the gate may deliver against it";
                case "TRF" -> "Approved, awaiting dispatch";
                default -> "Approved, awaiting posting";
            };
            case "POSTED" -> "Posted" + (postedAt == null ? "" : " " + postedAt.format(DAY))
                    + (postedBy == null ? "" : " by " + postedBy);
            case "REJECTED" -> "Rejected: final, a correction is a new document";
            case "CANCELLED" -> "Cancelled" + (cancelReason == null ? "" : ": " + cancelReason);
            default -> status;
        };
    }
}
