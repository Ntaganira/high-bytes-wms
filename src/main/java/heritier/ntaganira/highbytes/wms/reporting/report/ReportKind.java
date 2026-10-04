package heritier.ntaganira.highbytes.wms.reporting.report;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.report
 * - File       : ReportKind.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The standard reports: their address, title, what they answer, and whether they cover a period
 * </pre>
 */

import java.util.Arrays;
import java.util.Optional;

/**
 * The standard set (chosen 2 October 2026), each exportable to PDF and Excel (FR-RPT-04). A report reaches no
 * further than the screen it summarises: besides {@code report.view}, each needs that screen's own right.
 */
public enum ReportKind {

    STOCK_VALUATION("stock-valuation", "Stock valuation", "bi-cash-stack", false, "stock.view",
            "What the branch holds now, place by place, at the ledger's average cost."),
    MOVEMENTS("movements", "Movement register", "bi-arrow-left-right", true, "stock.view",
            "Every movement in the period with the ticket and the document behind it."),
    DISPATCHES("dispatches", "Dispatches", "bi-truck", true, "dispatch.view",
            "What left through the gate, on which note, against which authorization or cutting order, for whom."),
    DAMAGE("damage", "Write-offs, losses and returns", "bi-exclamation-triangle", true, "damage.view",
            "Stock written off, lost in transit, or brought back by customers, at the value the ledger moved."),
    VARIANCES("variances", "Count variances", "bi-graph-up-arrow", true, "count.view",
            "What posted counts found against the book, and the value adjusted."),
    DAILY_CLOSE("daily-close", "Daily closes", "bi-calendar-check", true, "close.view",
            "Each day's figures, its exceptions, who reconciled it and when it was locked.");

    private final String key;
    private final String title;
    private final String icon;
    private final boolean dated;
    private final String right;
    private final String description;

    ReportKind(String key, String title, String icon, boolean dated, String right, String description) {
        this.key = key;
        this.title = title;
        this.icon = icon;
        this.dated = dated;
        this.right = right;
        this.description = description;
    }

    /** The right of the screen this report summarises, needed besides report.view. */
    public String right()       { return right; }

    public String key()         { return key; }
    public String title()       { return title; }
    public String icon()        { return icon; }
    public boolean dated()      { return dated; }
    public String description() { return description; }

    public static Optional<ReportKind> of(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }
}
