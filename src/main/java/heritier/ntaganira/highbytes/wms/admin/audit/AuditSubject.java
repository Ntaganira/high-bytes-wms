package heritier.ntaganira.highbytes.wms.admin.audit;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.audit
 * - File       : AuditSubject.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The kinds of record the audit trail names, in words, and where each is read
 * </pre>
 */

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * The kinds of record the trail names ({@code audit_log.entity_name}), in words, and the page each one is read
 * on. A link reaches a page only as far as the reader's rights do: the page itself decides.
 */
public enum AuditSubject {

    APP_USER("app_user", "User account", "/admin/users/"),
    SIGN_IN("sign_in", "Sign-in", "/admin/users/"),
    ROLE("role", "Role", "/admin/roles/"),
    BRANCH("branch", "Branch", "/admin/branches/"),
    WORKFLOW("workflow_definition", "Approval chain", null),
    ITEM("item", "Item", "/items/"),
    LOCATION("location", "Location", "/locations/"),
    STORAGE_BIN("storage_bin", "Storage bin", null),
    SUPPLIER("supplier", "Supplier", "/suppliers/"),
    CUSTOMER("customer", "Customer", "/customers/"),
    DOCUMENT("document", "Document", null),
    DAILY_CLOSE("daily_close", "Daily close", null);

    private final String entityName;
    private final String label;
    private final String pathPrefix;

    AuditSubject(String entityName, String label, String pathPrefix) {
        this.entityName = entityName;
        this.label = label;
        this.pathPrefix = pathPrefix;
    }

    public String entityName() { return entityName; }
    public String label()      { return label; }

    public static Optional<AuditSubject> of(String entityName) {
        return Arrays.stream(values()).filter(s -> s.entityName.equals(entityName)).findFirst();
    }

    /** The kind in words; an unknown kind reads as stored. */
    public static String labelOf(String entityName) {
        return of(entityName).map(AuditSubject::label).orElse(entityName);
    }

    /** Where the record is read, when it has a page of its own. */
    public static String pathOf(String entityName, UUID entityId) {
        if (entityId == null) return null;
        if ("workflow_definition".equals(entityName)) return "/admin/workflows";
        return of(entityName).map(s -> s.pathPrefix).map(p -> p + entityId).orElse(null);
    }
}
