package heritier.ntaganira.highbytes.wms.inventory.lookup;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.lookup
 * - File       : BinOption.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A storage bin a line may name, as the forms offer it
 * </pre>
 */

import java.util.UUID;

/** A bin as a picker offers it, carrying the location it belongs to so a form can narrow by place. */
public record BinOption(UUID id, UUID locationId, String code, String zone) {
    public String label() { return zone == null ? code : code + " · " + zone; }
}
