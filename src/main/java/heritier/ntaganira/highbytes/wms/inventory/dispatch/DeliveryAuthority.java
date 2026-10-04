package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DeliveryAuthority.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A document a delivery note may answer to: a delivery authorization, or a posted cutting order
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.gate.ReleaseGate;
import java.util.List;
import java.util.UUID;

/**
 * What the gate needs from the document a delivery note answers to (V12, V18): who the goods are for, where they
 * leave from, what may be loaded, and whether it is released. A delivery authorization is one; a cutting order,
 * once posted, is another. The database judges the note against either; this lets the note's screens read either.
 */
public interface DeliveryAuthority {

    /** The {@code document_type.code} this serves: DAO or CUT. */
    String typeCode();

    /** The authority, read as an authorization: customer, place, customs reference, its creator and state. */
    DaoHeader asAuthorization(UUID id);

    /** What a note against it loads, one row per line, in the unit it is loaded in. */
    List<DaoLineRow> loadableLines(UUID id);

    /** Whether its goods may leave now, and if not, why. */
    ReleaseGate gateOf(UUID id);
}
