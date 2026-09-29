package heritier.ntaganira.highbytes.wms.security;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.security
 * - File       : AccessChangeRefusedException.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A change to someone's access that a control refuses, with the reason to show
 * </pre>
 */

/**
 * A change to someone's access that a control refuses: a segregation rule,
 * invariant 8, a change to one's own account, a grant that would rewrite
 * history. The message is written for the administrator, and says which
 * rule and why, never just "not allowed".
 */
public class AccessChangeRefusedException extends RuntimeException {

    public AccessChangeRefusedException(String reason) {
        super(reason);
    }
}
