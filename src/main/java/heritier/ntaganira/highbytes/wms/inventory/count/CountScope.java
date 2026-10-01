package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : CountScope.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What a count covers: a whole location, or the items chosen at it
 * </pre>
 */

/**
 * The scope is fixed when the count opens, because it decides what the ledger
 * refuses while the count is open (V15): a full count freezes the whole
 * location, a cycle count only the items on its sheet.
 */
public enum CountScope {

    FULL("Full count", "Every place holding stock at the location is on the sheet, written by the database. "
            + "Nothing moves in or out of the location until the verification count is signed."),
    PARTIAL("Cycle count", "The items you choose, in every bin that holds them. Only those items are frozen "
            + "at the location while the count is open.");

    private final String title;
    private final String summary;

    CountScope(String title, String summary) {
        this.title = title;
        this.summary = summary;
    }

    public String title()   { return title; }
    public String summary() { return summary; }

    public static CountScope parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
