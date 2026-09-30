package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : StepCheck.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The answer to "can this user take this action, and if not, why"
 * </pre>
 */

/**
 * Whether the signed-in user may take an action on a document, and when not,
 * the plain reason: "You raised this receipt, so you cannot sign step 2."
 *
 * <p>The screens use it to show only the actions available and to explain the
 * ones that are not. It is advice: the action itself is checked again, and
 * the database has the last word.
 */
public record StepCheck(boolean allowed, String reason, ChainStep step) {

    public static StepCheck yes(ChainStep step) {
        return new StepCheck(true, null, step);
    }

    public static StepCheck no(ChainStep step, String reason) {
        return new StepCheck(false, reason, step);
    }

    public boolean isAllowed() { return allowed; }
    public String getReason()  { return reason; }
    public ChainStep getStep() { return step; }
}
