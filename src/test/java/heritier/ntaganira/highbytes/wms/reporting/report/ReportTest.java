package heritier.ntaganira.highbytes.wms.reporting.report;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.report
 * - File       : ReportTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The standard reports read what the ledger and the documents hold, leave counted places out, and export
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.count.CountScope;
import heritier.ntaganira.highbytes.wms.inventory.count.CountService;
import heritier.ntaganira.highbytes.wms.inventory.cutting.CuttingService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DeliveryNoteService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.support.CountFlow;
import heritier.ntaganira.highbytes.wms.support.CuttingFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a real PostgreSQL 16 with Flyway V1 to V18, at places of the test's own. */
class ReportTest extends IntegrationTest {

    @Autowired ReportService reports;
    @Autowired KpiService kpis;
    @Autowired ReportExporter exporter;
    @Autowired ReceivingService receiving;
    @Autowired CountService counts;
    @Autowired CuttingService cutting;
    @Autowired DeliveryNoteService notes;

    LocalDate today = LocalDate.now(heritier.ntaganira.highbytes.wms.common.db.KigaliTime.ZONE);

    private UUID reader() {
        UUID id = fx.user("rpt", "FINANCE");
        fx.actAs(id);
        return id;
    }

    private static boolean anyCell(ReportTable t, String text) {
        return t.rows().stream().anyMatch(r -> r.stream().anyMatch(c -> c != null && c.toString().contains(text)));
    }

    @Test
    void theValuationAndTheRegisterReadTheLedgerAndLeaveACountedPlaceOut() {
        CountFlow cf = new CountFlow(fx, counts, receiving);
        cf.stock("10", true, "4");
        reader();
        ReportTable valuation = reports.build(ReportKind.STOCK_VALUATION, fx.kigali(), today, today);
        assertThat(valuation.rows()).filteredOn(r -> cf.locationCode.equals(r.get(2))).hasSize(2);
        ReportTable register = reports.build(ReportKind.MOVEMENTS, fx.kigali(), today, today);
        assertThat(register.rows()).filteredOn(r -> cf.locationCode.equals(r.get(5))).hasSize(2);

        // A live count on the glass: its place leaves both, and both say why.
        cf.open(CountScope.PARTIAL, cf.grn.glass);
        reader();
        valuation = reports.build(ReportKind.STOCK_VALUATION, fx.kigali(), today, today);
        assertThat(valuation.rows()).filteredOn(r -> cf.locationCode.equals(r.get(2))).singleElement()
                .satisfies(r -> assertThat((BigDecimal) r.get(4)).isEqualByComparingTo("4"));
        assertThat(valuation.notes()).anyMatch(n -> n.contains("live count are left out"));
        register = reports.build(ReportKind.MOVEMENTS, fx.kigali(), today, today);
        assertThat(register.rows()).filteredOn(r -> cf.locationCode.equals(r.get(5))).hasSize(1);
    }

    @Test
    void aCutDeliveryIsADispatchAgainstItsCuttingOrder() {
        CuttingFlow cut = new CuttingFlow(fx, cutting, notes, receiving);
        UUID order = cut.posted(cut.standardForm());
        cut.postNote(cut.raiseNote(order));
        String serial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", order)
                .query(String.class).single();
        reader();
        ReportTable dispatches = reports.build(ReportKind.DISPATCHES, fx.kigali(), today, today);
        assertThat(dispatches.rows()).filteredOn(r -> serial.equals(r.get(2))).singleElement()
                .satisfies(r -> {
                    assertThat((BigDecimal) r.get(5)).isEqualByComparingTo("6");
                    assertThat((BigDecimal) r.get(6)).isEqualByComparingTo("168421.05");
                });
        assertThat(dispatches.totals()).isNotNull();
        // The KPIs see the cut: some waste on the branch's posted orders.
        assertThat(kpis.kpis(fx.kigali())).filteredOn(k -> k.label().equals("Cutting waste")).singleElement()
                .satisfies(k -> assertThat(k.value()).endsWith("%"));
    }

    @Test
    void aCountsVariancesAreReportedOnlyOncePosted() {
        CountFlow cf = new CountFlow(fx, counts, receiving);
        cf.stock("10", true, "4");
        UUID id = cf.approved(l -> l.itemId().equals(cf.grn.glass) ? "9" : "4");
        String serial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", id).query(String.class).single();
        reader();
        assertThat(anyCell(reports.build(ReportKind.VARIANCES, fx.kigali(), today, today), serial)).isFalse();
        cf.post(id);
        reader();
        ReportTable variances = reports.build(ReportKind.VARIANCES, fx.kigali(), today, today);
        assertThat(variances.rows()).filteredOn(r -> serial.equals(r.get(1))).singleElement()
                .satisfies(r -> assertThat((BigDecimal) r.get(6)).isEqualByComparingTo("-1"));
    }

    @Test
    void everyReportRendersAndExportsToPdfAndExcel() throws Exception {
        fx.actAs(fx.user("ic", "INTERNAL_CTRL"));
        for (ReportKind kind : ReportKind.values()) {
            ReportTable table = reports.build(kind, fx.kigali(), today.minusDays(30), today);
            byte[] pdf = exporter.pdf(table);
            assertThat(new String(pdf, 0, 4)).as(kind + " PDF").isEqualTo("%PDF");
            byte[] xlsx = exporter.xlsx(table);
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
                assertThat(book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).contains(table.title());
            }
        }
        assertThat(kpis.kpis(fx.kigali())).hasSize(6)
                .extracting(KpiService.Kpi::label).contains("Inventory accuracy", "Time to release");
    }

    @Test
    void reportsAreReadOnlyWithTheRightAndEachWithItsOwnScreensRightToo() {
        fx.actAs(fx.user("sales", "SALES"));
        assertThatThrownBy(() -> reports.build(ReportKind.STOCK_VALUATION, fx.kigali(), today, today))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> kpis.kpis(fx.kigali())).isInstanceOf(AccessDeniedException.class);

        // The Internal Controller reads every report: it holds every view right.
        fx.actAs(fx.user("ic", "INTERNAL_CTRL"));
        for (ReportKind kind : ReportKind.values()) {
            assertThat(reports.readable(kind)).as(kind.key()).isTrue();
        }
        // A role created at runtime may carry report.view alone: it reads no report, since each needs its own
        // screen's right too, and the KPIs it sees are only the ones that need none.
        var ic = fx.actAs(fx.user("ic2", "INTERNAL_CTRL"));
        var onlyReports = new heritier.ntaganira.highbytes.wms.security.AppUserDetails(ic.id(), ic.username(),
                ic.fullName(), ic.passwordHash(), ic.homeBranchId(), ic.primaryRoleName(), true, false, null,
                java.util.Set.of("report.view"), ic.roleIds(), ic.branchId(), ic.accessibleBranchIds(), List.of(),
                ic.securityStamp(), ic.sessionEpoch(), ic.loadedOn());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        onlyReports, null, onlyReports.getAuthorities()));
        for (ReportKind kind : ReportKind.values()) {
            assertThat(reports.readable(kind)).as(kind.key()).isFalse();
        }
        assertThatThrownBy(() -> reports.build(ReportKind.MOVEMENTS, fx.kigali(), today, today))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("stock.view");
        assertThat(kpis.kpis(fx.kigali())).extracting(KpiService.Kpi::label).containsExactly("Signatures overdue");
    }

    @Test
    void thePeriodIsTheMonthToDateAndNeverBackwardsOrLongerThanAYear() {
        var p = ReportController.Period.of(null, null);
        assertThat(p.from()).isEqualTo(today.withDayOfMonth(1));
        assertThat(p.to()).isEqualTo(today);
        assertThat(ReportController.Period.of(today, today.minusDays(1)).problem()).contains("ends before");
        assertThat(ReportController.Period.of(today.minusYears(2), today).problem()).contains("at most a year");
        assertThat(List.of(ReportKind.values())).extracting(ReportKind::key)
                .containsExactly("stock-valuation", "movements", "dispatches", "damage", "variances", "daily-close");
    }
}
