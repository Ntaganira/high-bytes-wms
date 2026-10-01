package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : DamageRow.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One line of the return and damage report list
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** A report in the list: its kind, state, what it is about and who has to sign next. */
public record DamageRow(
        UUID id,
        String serialNo,
        String status,
        DamageKind kind,
        String reasonCode,
        LocalDate documentDate,
        String location,
        String about,
        int lineCount,
        String createdByName,
        String awaitingRole,
        boolean awaitingMe,
        LocalDateTime postedAt
) {

    public String reasonLabel() {
        return DamageKind.reasonLabel(reasonCode);
    }

    public DamageRow markAwaitingMe(boolean mine) {
        return new DamageRow(id, serialNo, status, kind, reasonCode, documentDate, location, about, lineCount,
                createdByName, awaitingRole, mine, postedAt);
    }
}
