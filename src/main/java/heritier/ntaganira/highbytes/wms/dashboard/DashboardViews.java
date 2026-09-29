package heritier.ntaganira.highbytes.wms.dashboard;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Everything the dashboard renders. */
public final class DashboardViews {

    private DashboardViews() {}

    /**
     * The figures the Board asked for, not generic counts. "Total items:
     * 4,832" tells a warehouse manager nothing; "7 releases held, 3 over
     * 24 hours" sends them somewhere.
     */
    public record Kpi(String stockValueDisplay,
                      String stockTrendPercent,
                      boolean stockTrendUp,
                      long receivedThisWeek,
                      int receiptNoteCount,
                      int releasesHeld,
                      int releasesOver24h,
                      String inventoryAccuracy,
                      boolean accuracyOnTarget) {

        /** Nothing posted yet: show zeroes rather than an error. */
        public static Kpi empty() {
            return new Kpi("0.00", "0.0", true, 0, 0, 0, 0, "—", true);
        }
    }

    public record PendingApproval(UUID documentId,
                                  String serialNo,
                                  String status,
                                  BigDecimal value,
                                  String summary,
                                  String awaitingRole,
                                  long hoursWaiting) {}

    public record RecentMovement(UUID documentId,
                                 String serialNo,
                                 String documentType,
                                 String itemDescription,
                                 BigDecimal quantity,
                                 String status) {}

    public record LowStockRow(String itemDescription,
                              String binCode,
                              BigDecimal onHand,
                              BigDecimal reorderLevel,
                              boolean critical) {}

    public record ChartSeries(String subtitle,
                              List<String> labels,
                              List<Long> received,
                              List<Long> dispatched) {}
}
