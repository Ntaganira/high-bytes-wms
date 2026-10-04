package heritier.ntaganira.highbytes.wms.inventory.lookup;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.lookup
 * - File       : LocationOption.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A place a document may name, as the forms offer it
 * </pre>
 */

import java.util.UUID;

/**
 * A location as a picker offers it. Shared by every module whose form names a
 * place: receiving, dispatch, transfers, counts, damage, cutting and the
 * cutover. It describes a choice on a screen, not a receipt, which is why it
 * lives here rather than in the module that happened to need it first.
 */
public record LocationOption(UUID id, String code, String name, boolean bonded) {
    public String label() { return code + " — " + name + (bonded ? " (bonded)" : ""); }
}
