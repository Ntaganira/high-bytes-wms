package heritier.ntaganira.highbytes.wms.common.db;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common.db
 * - File       : ContentionException.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Two requests met on the same rows and one was told to try again; not a control refusal
 * </pre>
 */

/**
 * A deadlock or serialization failure (40P01, 40001): nothing was changed and
 * the user is asked to try again. It is a {@link ControlRefusedException} so
 * the screens show its message like any other reason, but no control refused
 * anything, so it is logged as an error and never written to the audit trail
 * as a REJECT.
 */
public class ContentionException extends ControlRefusedException {

    public static final String MESSAGE =
            "Another posting touched the same stock at the same moment, so nothing was changed. Try again.";

    public ContentionException() {
        super(MESSAGE);
    }
}
