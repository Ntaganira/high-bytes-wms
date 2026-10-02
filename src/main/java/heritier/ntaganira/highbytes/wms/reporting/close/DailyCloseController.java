package heritier.ntaganira.highbytes.wms.reporting.close;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.close
 * - File       : DailyCloseController.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The daily close screens: the register, a day, reconcile, countersign, return
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
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
import java.util.List;

/**
 * The daily close at the branch the user is working in. Reading is close.view; reconciling is Finance's
 * (close.reconcile); countersigning, which locks the day, and returning are the Internal Controller's (close.lock).
 */
@Controller
@RequestMapping("/daily-close")
@PreAuthorize("hasAuthority('close.view')")
public class DailyCloseController {

    private final DailyCloseService closes;
    private final AuditService audit;

    public DailyCloseController(DailyCloseService closes, AuditService audit) {
        this.closes = closes;
        this.audit = audit;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "dailyClose";
    }

    @GetMapping
    public String register(@ModelAttribute("currentBranch") BranchView branch, Model model) {
        model.addAttribute("register", closes.register(branch.id()));
        return "daily-close/list";
    }

    @GetMapping("/{date}")
    public String day(@ModelAttribute("currentBranch") BranchView branch,
                      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                      Model model, RedirectAttributes redirect) {
        DailyCloseService.Detail detail;
        try {
            detail = closes.detail(branch.id(), date);
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/daily-close";
        }
        model.addAttribute("d", detail);
        model.addAttribute("close", detail.close());
        model.addAttribute("actions", detail.actions());
        model.addAttribute("history", detail.close().id() == null
                ? List.of() : audit.historyOf(DailyCloseService.ENTITY, detail.close().id(), 40));
        return "daily-close/view";
    }

    @PostMapping("/{date}/reconcile")
    @PreAuthorize("hasAuthority('close.reconcile')")
    public String reconcile(@ModelAttribute("currentBranch") BranchView branch,
                            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            closes.reconcile(branch.id(), date, note);
            redirect.addFlashAttribute("flashSuccess", "Reconciled. The day awaits the Internal Controller's "
                    + "countersignature, which locks it.");
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            redirect.addFlashAttribute("note", note);
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("flashError", DbRefusal.reason(e).orElseThrow(() -> e));
            redirect.addFlashAttribute("note", note);
        }
        return "redirect:/daily-close/" + date;
    }

    @PostMapping("/{date}/countersign")
    @PreAuthorize("hasAuthority('close.lock')")
    public String countersign(@ModelAttribute("currentBranch") BranchView branch,
                              @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                              @RequestParam(defaultValue = "false") boolean confirm, RedirectAttributes redirect) {
        if (!confirm) {
            redirect.addFlashAttribute("flashError", "Countersigning was not confirmed. Tick the confirmation to lock "
                    + "the day: a locked day is never reopened.");
            return "redirect:/daily-close/" + date;
        }
        try {
            closes.countersign(branch.id(), date);
            redirect.addFlashAttribute("flashSuccess", "Countersigned. " + DailyClose.day(date)
                    + " is locked: nothing can be dated into it, or before it.");
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("flashError", DbRefusal.reason(e).orElseThrow(() -> e));
        }
        return "redirect:/daily-close/" + date;
    }

    @PostMapping("/{date}/return")
    @PreAuthorize("hasAuthority('close.lock')")
    public String returnForReconciliation(@ModelAttribute("currentBranch") BranchView branch,
                                          @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                          @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        try {
            closes.returnForReconciliation(branch.id(), date, reason);
            redirect.addFlashAttribute("flashSuccess", "Returned to Finance to be reconciled again.");
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("flashError", DbRefusal.reason(e).orElseThrow(() -> e));
        }
        return "redirect:/daily-close/" + date;
    }
}
