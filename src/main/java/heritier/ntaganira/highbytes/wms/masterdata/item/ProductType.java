package heritier.ntaganira.highbytes.wms.masterdata.item;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.item
 * - File       : ProductType.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What kind of thing an item is: glass, silicone, steel, hardware, consumable, other
 * </pre>
 */

/**
 * What kind of thing an item is.
 *
 * <p>Matches the CHECK constraint on {@code item.product_type}. GLASS is the
 * one the whole system is shaped around: it is the only type that must carry
 * a thickness, because thickness is what the Internal Controller physically
 * measures on every receipt and every dispatch.
 */
public enum ProductType {

    GLASS("Glass", true),
    SILICONE("Silicone", false),
    STEEL("Stainless steel", false),
    HARDWARE("Hardware", false),
    CONSUMABLE("Consumable", false),
    OTHER("Other", false);

    private final String label;
    private final boolean requiresThickness;

    ProductType(String label, boolean requiresThickness) {
        this.label = label;
        this.requiresThickness = requiresThickness;
    }

    public String label() { return label; }

    /** Glass without a thickness cannot be verified at the gate, so it is refused. */
    public boolean requiresThickness() { return requiresThickness; }
}
