package heritier.ntaganira.highbytes.wms.reporting.report;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.report
 * - File       : ReportController.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Reports & KPIs: the KPIs and the report list, each report on screen, as a PDF and as a workbook
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

/**
 * {@code /reports}, read-only, at the branch the reader works in (the URL rule asks for {@code report.view}; each
 * report also needs its own screen's right, {@link ReportKind#right}). A dated report covers the period asked for,
 * by default the month to date; a period longer than a year, or one ending before it starts, is refused rather
 * than run.
 *
 * <p>An export is not recorded in the audit log: {@code audit_log.action} has no EXPORT, and adding one is a
 * migration (TODO.md). What a file holds is what the screen shows the same reader.
 */
@Controller
@RequestMapping("/reports")
@PreAuthorize("hasAuthority('report.view')")
public class ReportController {

    static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportService reports;
    private final KpiService kpis;
    private final ReportExporter exporter;

    public ReportController(ReportService reports, KpiService kpis, ReportExporter exporter) {
        this.reports = reports;
        this.kpis = kpis;
        this.exporter = exporter;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "reports";
    }

    @GetMapping
    public String index(@ModelAttribute("currentBranch") BranchView branch, Model model) {
        model.addAttribute("kpis", kpis.kpis(branch.id()));
        model.addAttribute("kinds", java.util.Arrays.stream(ReportKind.values()).filter(reports::readable).toList());
        return "reports/index";
    }

    @GetMapping("/{key}")
    public String view(@ModelAttribute("currentBranch") BranchView branch,
                       @PathVariable String key,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       Model model) {
        ReportKind kind = kindOf(key);
        Period p = kind.dated() ? Period.of(from, to) : Period.of(null, null);
        model.addAttribute("kind", kind);
        model.addAttribute("from", p.from());
        model.addAttribute("to", p.to());
        if (p.problem() != null) {
            model.addAttribute("periodProblem", p.problem());
            return "reports/view";
        }
        model.addAttribute("report", reports.build(kind, branch.id(), p.from(), p.to()));
        return "reports/view";
    }

    @GetMapping("/{key}/pdf")
    public ResponseEntity<byte[]> pdf(@ModelAttribute("currentBranch") BranchView branch,
                                      @PathVariable String key,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReportKind kind = kindOf(key);
        Period p = (kind.dated() ? Period.of(from, to) : Period.of(null, null)).orRefuse();
        return file(exporter.pdf(reports.build(kind, branch.id(), p.from(), p.to())), MediaType.APPLICATION_PDF,
                fileName(kind, branch, p, "pdf"));
    }

    @GetMapping("/{key}/xlsx")
    public ResponseEntity<byte[]> xlsx(@ModelAttribute("currentBranch") BranchView branch,
                                       @PathVariable String key,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReportKind kind = kindOf(key);
        Period p = (kind.dated() ? Period.of(from, to) : Period.of(null, null)).orRefuse();
        return file(exporter.xlsx(reports.build(kind, branch.id(), p.from(), p.to())), XLSX,
                fileName(kind, branch, p, "xlsx"));
    }

    // ---- helpers ----------------------------------------------------------------------

    private static ReportKind kindOf(String key) {
        return ReportKind.of(key).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No report " + key));
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setContentDisposition(ContentDisposition.attachment().filename(name).build());
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static String fileName(ReportKind kind, BranchView branch, Period p, String extension) {
        String dates = kind.dated() ? p.from() + "_" + p.to() : LocalDate.now(KigaliTime.ZONE).toString();
        return kind.key() + "-" + branch.code() + "-" + dates + "." + extension;
    }

    /** The period asked for: the month to date by default, at most a year, never ending before it starts. */
    record Period(LocalDate from, LocalDate to, String problem) {

        static Period of(LocalDate from, LocalDate to) {
            LocalDate today = LocalDate.now(KigaliTime.ZONE);
            LocalDate t = to == null ? today : to;
            LocalDate f = from == null ? t.withDayOfMonth(1) : from;
            if (t.isBefore(f)) return new Period(f, t, "The period ends before it starts.");
            if (f.plusYears(1).isBefore(t)) return new Period(f, t, "A report covers at most a year: narrow the period.");
            return new Period(f, t, null);
        }

        Period orRefuse() {
            if (problem != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
            return this;
        }
    }
}
