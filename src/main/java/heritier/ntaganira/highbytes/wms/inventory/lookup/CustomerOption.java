package heritier.ntaganira.highbytes.wms.inventory.lookup;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.lookup
 * - File       : CustomerOption.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A customer a document may name, as the forms offer it
 * </pre>
 */

import java.util.UUID;

/** A customer as a picker offers it: a delivery authorization names one, and so does a cutting order. */
public record CustomerOption(UUID id, String code, String name) {
    public String label() { return code + " — " + name; }
}
