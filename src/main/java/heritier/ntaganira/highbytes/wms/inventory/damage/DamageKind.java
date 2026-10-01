package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : DamageKind.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The four kinds of return and damage report and the reason codes each may carry
 * </pre>
 */

import java.util.List;

/**
 * The kinds of report, one per report and fixed when it is raised (V14). Each has
 * its own shape: where stock leaves from, where it arrives, what it is about.
 */
public enum DamageKind {

    WRITE_OFF("Write-off",
            "Damaged, expired or missing stock written off from a warehouse, bonded or quarantine location, at average cost.",
            List.of("DAMAGED", "EXPIRED", "MISSING", "OTHER")),
    TRANSIT_LOSS("Loss in transit",
            "Stock that left on a transfer and never arrived, written off from the source branch's transit location at the cost it was dispatched at.",
            List.of("LOST_IN_TRANSIT", "DAMAGED", "MISSING", "OTHER")),
    CUSTOMER_RETURN("Customer return",
            "Goods a customer sent back against a delivery note, received into quarantine at the cost they left with.",
            List.of("CUSTOMER_RETURN", "DAMAGED", "OTHER")),
    QUARANTINE_RELEASE("Quarantine release",
            "Stock that passed inspection, moved from quarantine into a sellable location at no change of value.",
            List.of("INSPECTION_PASSED", "OTHER"));

    private final String title;
    private final String summary;
    private final List<String> reasonCodes;

    DamageKind(String title, String summary, List<String> reasonCodes) {
        this.title = title;
        this.summary = summary;
        this.reasonCodes = reasonCodes;
    }

    public String title()             { return title; }
    public String summary()           { return summary; }
    public List<String> reasonCodes() { return reasonCodes; }

    public String getTitle()             { return title; }
    public String getSummary()           { return summary; }
    public List<String> getReasonCodes() { return reasonCodes; }
    public String getName()              { return name(); }

    /** A reason code with its label, for a select. */
    public record ReasonOption(String code, String label) {}

    public List<ReasonOption> reasonOptions() {
        return reasonCodes.stream().map(c -> new ReasonOption(c, reasonLabel(c))).toList();
    }

    /** The stock leaves through a DAMAGE ticket (write-offs and losses). */
    public boolean writesOff() { return this == WRITE_OFF || this == TRANSIT_LOSS; }

    public static DamageKind parse(String value) {
        if (value == null) return null;
        for (DamageKind kind : values()) {
            if (kind.name().equals(value)) return kind;
        }
        return null;
    }

    /** A reason code as people read it. */
    public static String reasonLabel(String code) {
        return switch (code) {
            case "DAMAGED" -> "Damaged";
            case "EXPIRED" -> "Expired";
            case "MISSING" -> "Missing";
            case "LOST_IN_TRANSIT" -> "Lost in transit";
            case "CUSTOMER_RETURN" -> "Returned by the customer";
            case "INSPECTION_PASSED" -> "Passed inspection";
            default -> "Other";
        };
    }
}
