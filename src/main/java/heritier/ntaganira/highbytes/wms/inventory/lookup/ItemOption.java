package heritier.ntaganira.highbytes.wms.inventory.lookup;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.lookup
 * - File       : ItemOption.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : An item a line may name, as the forms offer it
 * </pre>
 */

import java.util.UUID;

/**
 * An item as a picker offers it. {@code glass()} is what every form uses to
 * decide whether the measured thickness is required, so the rule is asked in
 * one place rather than restated per module.
 */
public record ItemOption(UUID id, String code, String description, String productType, String baseUomCode) {
    public String label() { return code + " — " + description; }
    public boolean glass() { return "GLASS".equals(productType); }
}
