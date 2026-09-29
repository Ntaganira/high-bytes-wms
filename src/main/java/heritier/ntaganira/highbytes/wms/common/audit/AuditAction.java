package heritier.ntaganira.highbytes.wms.common.audit;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common.audit
 * - File       : AuditAction.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The actions the audit log records
 * </pre>
 */

/**
 * The actions the audit log records.
 *
 * <p>Matches the CHECK constraint on {@code audit_log.action}. Adding one
 * here requires a migration widening that constraint — deliberately, so the
 * vocabulary cannot drift silently.
 */
public enum AuditAction {
    CREATE,
    UPDATE,
    APPROVE,
    REJECT,
    POST,
    CANCEL,
    DEACTIVATE,
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    ATTACH,
    DETACH
}
