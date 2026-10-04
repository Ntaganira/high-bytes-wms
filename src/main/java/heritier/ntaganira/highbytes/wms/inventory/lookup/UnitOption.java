package heritier.ntaganira.highbytes.wms.inventory.lookup;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.lookup
 * - File       : UnitOption.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A unit of measure a line may name, as the forms offer it
 * </pre>
 */

import java.util.UUID;

/** A unit as a picker offers it. The base quantity a line carries is derived from it server-side. */
public record UnitOption(UUID id, String code, String name) {
    public String label() { return code + " — " + name; }
}
