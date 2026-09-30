package heritier.ntaganira.highbytes.wms.common.db;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common.db
 * - File       : ControlRefusedException.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A control refused an action; the message is the reason a user reads
 * </pre>
 */

/**
 * A control refused what was asked, and the message says why in words a user
 * can act on.
 *
 * <p>Raised by the services when a rule they check first fails, and by
 * {@link DbRefusal} when the database refused (a workflow guard, a locked
 * business date, a malformed line). Either way the transaction rolls back and
 * the screen shows {@link #getMessage()} as the reason, never a stack trace.
 */
public class ControlRefusedException extends RuntimeException {

    public ControlRefusedException(String reason) {
        super(reason);
    }
}
