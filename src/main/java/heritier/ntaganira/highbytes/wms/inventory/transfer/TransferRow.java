package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : TransferRow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one row of a transfer list
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One line of the transfer lists (all, ready to dispatch, awaiting receipt). */
public record TransferRow(
        UUID id,
        String serialNo,
        String displayState,
        LocalDate documentDate,
        String fromBranchName,
        String fromCode,
        String toBranchName,
        String toCode,
        boolean bonded,
        int lineCount,
        String createdByName,
        String awaitingRole,
        boolean awaitingMe,
        LocalDateTime dispatchedAt
) {

    public TransferRow markAwaitingMe(boolean mine) {
        return new TransferRow(id, serialNo, displayState, documentDate, fromBranchName, fromCode, toBranchName,
                toCode, bonded, lineCount, createdByName, awaitingRole, mine, dispatchedAt);
    }
}
