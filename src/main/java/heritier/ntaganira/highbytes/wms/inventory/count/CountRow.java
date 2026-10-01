package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : CountRow.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A count in the list: where, how far it has got, and who signs next
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A count in the list. {@code varianceLines} is null while the book is not
 * readable: even how many lines differ from the book says something to a counter.
 */
public record CountRow(
        UUID id,
        String serialNo,
        String status,
        LocalDate documentDate,
        String locationCode,
        CountScope scope,
        int lineCount,
        int countedLines,
        Integer varianceLines,
        String createdByName,
        String awaitingRole,
        boolean awaitingMe,
        boolean frozen,
        LocalDateTime postedAt
) {

    public CountRow markAwaitingMe(boolean mine) {
        return new CountRow(id, serialNo, status, documentDate, locationCode, scope, lineCount, countedLines,
                varianceLines, createdByName, awaitingRole, mine, frozen, postedAt);
    }
}
