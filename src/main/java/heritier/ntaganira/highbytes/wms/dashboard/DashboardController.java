package heritier.ntaganira.highbytes.wms.dashboard;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.dashboard
 * - File       : DashboardController.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Serves the dashboard and switches the session's branch
 * </pre>
 */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import heritier.ntaganira.highbytes.wms.approval.ApprovalQueueService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.security.SessionAccess;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Controller
public class DashboardController {

    /** The oldest waiting signatures the dashboard shows; the queue shows them all. */
    static final int AWAITING_SHOWN = 5;

    private final DashboardService dashboard;
    private final ApprovalQueueService approvals;
    private final ObjectMapper json;

    public DashboardController(DashboardService dashboard, ApprovalQueueService approvals, ObjectMapper json) {
        this.dashboard = dashboard;
        this.approvals = approvals;
        this.json = json;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "dashboard";
    }

    @GetMapping("/")
    public String dashboard(@AuthenticationPrincipal AppUserDetails user,
                            @ModelAttribute("currentBranch") BranchView branch,
                            Model model) throws JsonProcessingException {

        if (branch == null) {
            model.addAttribute("flashError",
                    "No branch is configured for your account. Ask an administrator to assign one.");
            model.addAttribute("kpi", DashboardViews.Kpi.empty());
            model.addAttribute("chartDataJson", "{}");
            return "dashboard";
        }

        UUID branchId = branch.id();

        model.addAttribute("kpi", dashboard.kpis(branchId));
        model.addAttribute("pendingApprovals", approvals.awaiting(null, null, AWAITING_SHOWN));
        model.addAttribute("recentMovements", dashboard.recentMovements(branchId));
        model.addAttribute("lowStock", dashboard.lowStock(branchId));

        Map<String, DashboardViews.ChartSeries> series = new LinkedHashMap<>();
        series.put("7",  dashboard.movementSeries(branchId, 7,  "Sheets received vs dispatched, last 7 days"));
        series.put("30", dashboard.movementSeries(branchId, 30, "Sheets received vs dispatched, last 30 days"));
        model.addAttribute("chartDataJson", json.writeValueAsString(series));

        return "dashboard";
    }

    /**
     * Switching branch changes what every screen shows and which rights
     * apply, so it lives in the session and the user's permissions are
     * reloaded for it on the next request. Only branches where the user
     * holds a role are offered, and only those are accepted.
     *
     * <p>The approval queue switches to a document's branch and opens it: {@code next} is followed only when it
     * is a document's own address, so the form cannot be used to send anyone elsewhere.
     */
    @PostMapping("/branch/switch")
    public String switchBranch(@RequestParam UUID branchId,
                               @RequestParam(required = false) String next,
                               @AuthenticationPrincipal AppUserDetails user,
                               HttpSession session,
                               RedirectAttributes redirect) {
        if (!user.accessibleBranchIds().contains(branchId)) {
            redirect.addFlashAttribute("flashError",
                    "You hold no role at that branch, so you cannot work there.");
            return "redirect:/";
        }
        session.setAttribute(SessionAccess.BRANCH_SESSION_KEY, branchId);
        return "redirect:" + documentAddress(next).orElse("/");
    }

    /** {@code /documents/{uuid}} and nothing else. */
    static java.util.Optional<String> documentAddress(String next) {
        if (next == null || !next.startsWith(DOCUMENTS)) return java.util.Optional.empty();
        try {
            UUID id = UUID.fromString(next.substring(DOCUMENTS.length()));
            return java.util.Optional.of(DOCUMENTS + id);
        } catch (IllegalArgumentException notADocument) {
            return java.util.Optional.empty();
        }
    }

    private static final String DOCUMENTS = "/documents/";
}
