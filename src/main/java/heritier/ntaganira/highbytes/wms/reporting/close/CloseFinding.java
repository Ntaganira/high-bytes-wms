package heritier.ntaganira.highbytes.wms.reporting.close;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.close
 * - File       : CloseFinding.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One exception the database found in a day, in a sentence
 * </pre>
 */

/**
 * One exception the database found in a day ({@code close_exceptions}, V16): a kind and a sentence saying what
 * is wrong. A day with any is reconciled only with a note saying what they are and why it still closes.
 */
public record CloseFinding(String kind, String detail) {

    /** The kind in words, for a heading. */
    public String title() {
        return switch (kind) {
            case "OPENING"    -> "Opening differs from the last locked day";
            case "NEGATIVE"   -> "Below zero on the book";
            case "NOT_POSTED" -> "Approved, not on the ledger";
            case "CACHE"      -> "Balance cache differs from the ledger";
            case "RECONCILER_POSTED" -> "Reconciled by someone who posted in it";
            default           -> kind;
        };
    }
}
