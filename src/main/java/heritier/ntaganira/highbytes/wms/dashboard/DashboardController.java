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
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.web.GlobalModelAdvice;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Controller
public class DashboardController {

    private final DashboardService dashboard;
    private final ObjectMapper json;

    public DashboardController(DashboardService dashboard, ObjectMapper json) {
        this.dashboard = dashboard;
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
        model.addAttribute("pendingApprovals",
                dashboard.pendingFor(branchId, user.id(), user.roleIds()));
        model.addAttribute("recentMovements", dashboard.recentMovements(branchId));
        model.addAttribute("lowStock", dashboard.lowStock(branchId));

        Map<String, DashboardViews.ChartSeries> series = new LinkedHashMap<>();
        series.put("7",  dashboard.movementSeries(branchId, 7,  "Sheets received vs dispatched, last 7 days"));
        series.put("30", dashboard.movementSeries(branchId, 30, "Sheets received vs dispatched, last 30 days"));
        model.addAttribute("chartDataJson", json.writeValueAsString(series));

        return "dashboard";
    }

    /** Switching branch changes what every screen shows, so it lives in the session. */
    @PostMapping("/branch/switch")
    public String switchBranch(@RequestParam UUID branchId, HttpSession session) {
        session.setAttribute(GlobalModelAdvice.BRANCH_SESSION_KEY, branchId);
        return "redirect:/";
    }
}
