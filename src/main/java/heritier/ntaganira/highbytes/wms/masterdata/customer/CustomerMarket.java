package heritier.ntaganira.highbytes.wms.masterdata.customer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.customer
 * - File       : CustomerMarket.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The markets a customer belongs to
 * </pre>
 */

/** Where a customer is served from: the home market or one of the two export routes. */
public enum CustomerMarket {
    DOMESTIC("Domestic"),
    EXPORT_BUKAVU("Export, Bukavu"),
    EXPORT_GOMA("Export, Goma");

    private final String label;

    CustomerMarket(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
