package heritier.ntaganira.highbytes.wms.admin.branch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.branch
 * - File       : BranchAdminController.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Branch screens: list, create, view, edit, deactivate and reactivate
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.SmartValidator;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/admin/branches")
@PreAuthorize("hasAuthority('admin.branches')")
public class BranchAdminController {

    private final BranchAdminService branches;
    private final AuditService audit;
    private final SmartValidator validator;

    public BranchAdminController(BranchAdminService branches, AuditService audit, jakarta.validation.Validator validator) {
        this.branches = branches;
        this.audit = audit;
        this.validator = new SpringValidatorAdapter(validator);
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "branches";
    }

    @ModelAttribute("branchTypes")
    public BranchType[] branchTypes() {
        return BranchType.values();
    }

    // ---- list ------------------------------------------------------------

    @GetMapping
    public String list(Model model) {
        model.addAttribute("branches", branches.list());
        return "admin/branches/list";
    }

    // ---- create ----------------------------------------------------------

    @GetMapping("/new")
    public String newForm(Model model) {
        var form = new BranchForm();
        form.setBranchType(BranchType.BRANCH);
        model.addAttribute("form", form);
        return "admin/branches/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") BranchForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView current,
                         RedirectAttributes redirect) {
        form.setId(null);
        crossCheck(form, binding);
        if (binding.hasErrors()) return "admin/branches/form";
        try {
            UUID id = branches.create(form, current);
            redirect.addFlashAttribute("flashSuccess", "Branch " + form.getName() + " created. Its locations are"
                    + " added under Master data, and people are given roles there from their user page.");
            return "redirect:/admin/branches/" + id;
        } catch (BranchAdminService.BranchTakenException e) {
            binding.rejectValue(e.field(), "duplicate", e.getMessage());
        } catch (ControlRefusedException e) {
            binding.reject("refused", e.getMessage());
        }
        return "admin/branches/form";
    }

    // ---- view ------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var branch = branches.require(id);
        model.addAttribute("branch", branch);
        model.addAttribute("places", branches.locations(id));
        model.addAttribute("people", branches.people(id));
        model.addAttribute("blocker", branch.active() ? branches.deactivationBlocker(id) : null);
        model.addAttribute("history", audit.historyOf(BranchAdminService.ENTITY, id, 30));
        return "admin/branches/view";
    }

    // ---- edit ------------------------------------------------------------

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("form", branches.formFor(id));
        return "admin/branches/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @ModelAttribute("form") BranchForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView current,
                         RedirectAttributes redirect) {

        // Before validation, so an invalid form for a missing branch is a 404 too.
        BranchForm stored = branches.formFor(id);
        // The code never changes, and a branch with a past keeps its type: the stored values are validated and
        // shown, whatever was posted (a disabled field posts nothing).
        form.setId(id);
        form.setCode(stored.getCode());
        form.setHasHistory(stored.isHasHistory());
        if (stored.isHasHistory()) {
            form.setBranchType(stored.getBranchType());
            form.setBonded(stored.isBonded());
        }
        validator.validate(form, binding);
        crossCheck(form, binding);
        if (binding.hasErrors()) return "admin/branches/form";

        try {
            branches.update(id, form, current);
            redirect.addFlashAttribute("flashSuccess", "Branch " + form.getName() + " updated.");
            return "redirect:/admin/branches/" + id;
        } catch (BranchAdminService.BranchTakenException e) {
            binding.rejectValue(e.field(), "duplicate", e.getMessage());
        } catch (ControlRefusedException e) {
            binding.reject("refused", e.getMessage());
        }
        return "admin/branches/form";
    }

    // ---- state -----------------------------------------------------------

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable UUID id,
                             @RequestParam(required = false) String reason,
                             @ModelAttribute("currentBranch") BranchView current,
                             RedirectAttributes redirect) {
        try {
            branches.deactivate(id, reason, current);
            redirect.addFlashAttribute("flashSuccess", "Branch deactivated. Nothing new starts there; its records"
                    + " stay, and everyone's next page shows only the branches still open.");
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/branches/" + id;
    }

    @PostMapping("/{id}/reactivate")
    public String reactivate(@PathVariable UUID id,
                             @ModelAttribute("currentBranch") BranchView current,
                             RedirectAttributes redirect) {
        try {
            branches.reactivate(id, current);
            redirect.addFlashAttribute("flashSuccess", "Branch reactivated. Work may start there again.");
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/branches/" + id;
    }

    // ---- helpers ---------------------------------------------------------

    /** A bonded warehouse is bonded, and a bonded branch names its customs regime (V1, V17). */
    private static void crossCheck(BranchForm form, BindingResult binding) {
        if (form.getBranchType() == BranchType.BONDED && !form.isBonded()) {
            binding.addError(new FieldError("form", "bonded",
                    "A bonded warehouse is bonded: its stock is duty-suspended."));
        }
        if (form.isBonded() && form.getCustomsRegime() == null) {
            binding.addError(new FieldError("form", "customsRegime",
                    "A bonded branch names its customs regime: every receipt and release there carries a customs reference."));
        }
    }
}
