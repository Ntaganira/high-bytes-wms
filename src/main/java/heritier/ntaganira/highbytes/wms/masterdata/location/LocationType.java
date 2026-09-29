package heritier.ntaganira.highbytes.wms.masterdata.location;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.location
 * - File       : LocationType.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What a location is for: warehouse, bonded, quarantine, transit, cutting, van
 * </pre>
 */

/**
 * What a location is for.
 *
 * <p>Transit and quarantine are locations rather than statuses on purpose:
 * total stock is the sum of every location, so the ledger balances at every
 * instant and a consignment that never arrives shows as a balance sitting in
 * transit rather than an absence nobody notices.
 */
public enum LocationType {

    WAREHOUSE ("Warehouse",  true,  false, "Ordinary saleable stock."),
    BONDED    ("Bonded",     true,  true,  "Duty-suspended stock. Movements need a customs reference."),
    QUARANTINE("Quarantine", false, false, "Damaged or held stock, isolated from saleable stock but still counted."),
    TRANSIT   ("In transit", false, false, "Stock dispatched from one branch and not yet confirmed at another."),
    CUTTING   ("Cutting floor", true, false, "Sheets consumed by cutting orders; off-cuts return to a warehouse."),
    VAN       ("Van stock",  true,  false, "A Regional Sales Manager's load. Phase 4.");

    private final String label;
    private final boolean sellable;
    private final boolean bonded;
    private final String description;

    LocationType(String label, boolean sellable, boolean bonded, String description) {
        this.label = label;
        this.sellable = sellable;
        this.bonded = bonded;
        this.description = description;
    }

    public String label() { return label; }
    public boolean sellable() { return sellable; }
    public boolean bonded() { return bonded; }
    public String description() { return description; }

    /** A branch should have exactly one of each of these; the rest are optional. */
    public boolean isStructural() {
        return this == TRANSIT || this == QUARANTINE;
    }
}
