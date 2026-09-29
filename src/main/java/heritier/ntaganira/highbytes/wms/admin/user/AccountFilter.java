package heritier.ntaganira.highbytes.wms.admin.user;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.user
 * - File       : AccountFilter.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Which accounts the users list shows
 * </pre>
 */

/** Which accounts the users list shows. Deactivated accounts are hidden unless asked for. */
public enum AccountFilter {

    CURRENT("Current accounts"),
    LOCKED("Locked out"),
    INACTIVE("Deactivated"),
    ALL("All accounts");

    private final String label;

    AccountFilter(String label) {
        this.label = label;
    }

    public String label() { return label; }
}
