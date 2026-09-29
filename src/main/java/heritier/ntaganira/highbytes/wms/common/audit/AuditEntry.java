package heritier.ntaganira.highbytes.wms.common.audit;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common.audit
 * - File       : AuditEntry.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One logged change, as a reviewer reads it, with its field changes
 * </pre>
 */

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One logged change, as a reviewer reads it. */
public record AuditEntry(
        long id,
        String entityName,
        UUID entityId,
        String entityLabel,
        UUID documentId,
        String action,
        List<FieldChange> changes,
        String actorName,
        String actorUsername,
        String actorRole,
        String branchName,
        LocalDateTime occurredAt,
        String clientAddress,
        String reason
) {

    /**
     * A field that actually changed. Unchanged fields are never listed:
     * a reviewer asks what moved, not what stayed still.
     *
     * <p>Values are the labels a reader sees, resolved when the change was
     * made. Storing an id and resolving it at display time means a role
     * later renamed from "Admin" to "Administrator" rewrites every
     * historical entry — including the one that recorded the rename.
     */
    public record FieldChange(String fieldLabel, String before, String after) {}
}
