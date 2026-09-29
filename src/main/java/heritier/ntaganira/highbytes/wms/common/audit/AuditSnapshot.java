package heritier.ntaganira.highbytes.wms.common.audit;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common.audit
 * - File       : AuditSnapshot.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Builds the before/after snapshot pair for one audited change
 * </pre>
 */

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the before/after snapshot pair for one change.
 *
 * <p>Callers add each auditable field twice — once with the old value, once
 * with the new — and the writer stores both whole snapshots. The diff is
 * computed at display time, not here: a snapshot survives a field being
 * added or renamed later, where a stored diff does not.
 *
 * <pre>
 * AuditSnapshot.of()
 *     .field("Item code",   old.itemCode(),   form.itemCode())
 *     .field("Thickness",   old.thicknessMm(), form.thicknessMm())
 *     .field("Active",      old.active(),      form.active());
 * </pre>
 */
public final class AuditSnapshot {

    private final Map<String, Object> before = new LinkedHashMap<>();
    private final Map<String, Object> after  = new LinkedHashMap<>();

    private AuditSnapshot() {}

    public static AuditSnapshot of() {
        return new AuditSnapshot();
    }

    /** Record one field. Labels are what the reviewer reads, not column names. */
    public AuditSnapshot field(String label, Object oldValue, Object newValue) {
        before.put(label, display(oldValue));
        after.put(label, display(newValue));
        return this;
    }

    /** A field present only on creation, or only on deletion. */
    public AuditSnapshot value(String label, Object value) {
        after.put(label, display(value));
        return this;
    }

    public Map<String, Object> before() { return before; }
    public Map<String, Object> after()  { return after; }

    /** True when nothing actually moved — then there is nothing to log. */
    public boolean unchanged() {
        return before.equals(after);
    }

    private static Object display(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b ? "Yes" : "No";
        if (value instanceof Enum<?> e) return e.name();
        return value.toString();
    }
}
