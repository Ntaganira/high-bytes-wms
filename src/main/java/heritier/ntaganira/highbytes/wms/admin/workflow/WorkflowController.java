package heritier.ntaganira.highbytes.wms.admin.workflow;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.workflow
 * - File       : WorkflowController.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The Workflow Definitions screen: every chain read whole, and a switchover still to come moved
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Controller
@RequestMapping("/admin/workflows")
@PreAuthorize("hasAuthority('admin.workflow')")
public class WorkflowController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy");

    private final WorkflowService workflows;

    public WorkflowController(WorkflowService workflows) {
        this.workflows = workflows;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "workflows";
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("chains", workflows.chains());
        model.addAttribute("history", workflows.history(30));
        model.addAttribute("today", LocalDate.now(KigaliTime.ZONE));
        return "admin/workflows/list";
    }

    @PostMapping("/{id}/switchover")
    public String moveSwitchover(@PathVariable UUID id,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                 @RequestParam(required = false) String reason,
                                 @ModelAttribute("currentBranch") BranchView current,
                                 RedirectAttributes redirect) {
        try {
            workflows.moveSwitchover(id, date, reason, current);
            redirect.addFlashAttribute("flashSuccess", "Switchover moved: the next chain comes into force on "
                    + DAY.format(date) + ". Documents already raised finish under the chain they began with.");
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/workflows";
    }
}
