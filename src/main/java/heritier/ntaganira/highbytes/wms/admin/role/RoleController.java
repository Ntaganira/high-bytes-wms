package heritier.ntaganira.highbytes.wms.admin.role;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.role
 * - File       : RoleController.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Role screens: list, create, view, edit, permissions, deactivate and reactivate
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.security.AccessChangeRefusedException;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.SmartValidator;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/admin/roles")
@PreAuthorize("hasAuthority('admin.roles')")
public class RoleController {

    private final RoleService roles;
    private final AuditService audit;
    private final SmartValidator validator;

    public RoleController(RoleService roles, AuditService audit, jakarta.validation.Validator validator) {
        this.roles = roles;
        this.audit = audit;
        this.validator = new SpringValidatorAdapter(validator);
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "roles";
    }

    @ModelAttribute("structures")
    public RoleStructure[] structures() {
        return RoleStructure.values();
    }

    // ---- list ------------------------------------------------------------

    @GetMapping
    public String list(Model model) {
        model.addAttribute("roles", roles.list());
        model.addAttribute("rules", roles.segregationRules());
        return "admin/roles/list";
    }

    // ---- create ----------------------------------------------------------

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new RoleForm());
        return "admin/roles/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") RoleForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) return "admin/roles/form";
        try {
            UUID id = roles.create(form, branch);
            redirect.addFlashAttribute("flashSuccess",
                    "Role " + form.getName() + " created. It permits nothing until you choose its permissions.");
            return "redirect:/admin/roles/" + id + "/permissions";
        } catch (RoleService.RoleCodeTakenException e) {
            binding.rejectValue("code", "duplicate", e.getMessage());
            return "admin/roles/form";
        } catch (RoleService.RoleNameTakenException e) {
            binding.rejectValue("name", "duplicate", e.getMessage());
            return "admin/roles/form";
        }
    }

    // ---- view ------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var role = roles.findById(id).orElseThrow(() -> new RoleService.RoleNotFoundException(id));
        var granted = roles.permissionCodes(id);
        model.addAttribute("role", role);
        model.addAttribute("groups", roles.catalogue().stream()
                .map(g -> new PermissionGroup(g.module(), g.permissions().stream()
                        .filter(p -> granted.contains(p.code())).toList()))
                .filter(g -> !g.permissions().isEmpty())
                .toList());
        model.addAttribute("holders", roles.holders(id));
        model.addAttribute("steps", roles.chainSteps(id));
        model.addAttribute("segregation", roles.segregationOf(id));
        model.addAttribute("heldByMe", roles.heldByCurrentUser(id));
        model.addAttribute("history", audit.historyOf("role", id, 30));
        return "admin/roles/view";
    }

    // ---- edit ------------------------------------------------------------

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        var form = roles.formFor(id);
        if (roles.heldByCurrentUser(id)) {
            redirect.addFlashAttribute("flashError", "You hold " + form.getName()
                    + ", so another administrator changes it: nobody administers their own access.");
            return "redirect:/admin/roles/" + id;
        }
        if (roles.findById(id).map(RoleRow::policyDefined).orElse(false)) {
            redirect.addFlashAttribute("flashError", form.getName()
                    + " is defined by the policy, so its name and description change only by a reviewed migration.");
            return "redirect:/admin/roles/" + id;
        }
        model.addAttribute("form", form);
        return "admin/roles/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @ModelAttribute("form") RoleForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         RedirectAttributes redirect) {

        // Before validation, so an invalid form for a missing role is a 404 too.
        String code = roles.formFor(id).getCode();

        // The code never changes. Whatever was posted, the stored one is
        // validated and shown.
        form.setId(id);
        form.setCode(code);
        validator.validate(form, binding);
        if (binding.hasErrors()) return "admin/roles/form";

        try {
            roles.update(id, form, branch);
        } catch (RoleService.RoleNameTakenException e) {
            binding.rejectValue("name", "duplicate", e.getMessage());
            return "admin/roles/form";
        } catch (AccessChangeRefusedException e) {
            binding.reject("refused", e.getMessage());
            return "admin/roles/form";
        }
        redirect.addFlashAttribute("flashSuccess", "Role " + form.getName() + " updated.");
        return "redirect:/admin/roles/" + id;
    }

    // ---- permissions -----------------------------------------------------

    @GetMapping("/{id}/permissions")
    public String permissionsForm(@PathVariable UUID id, Model model) {
        var role = roles.findById(id).orElseThrow(() -> new RoleService.RoleNotFoundException(id));
        populatePermissions(role, roles.permissionCodes(id), model);
        return "admin/roles/permissions";
    }

    @PostMapping("/{id}/permissions")
    public String savePermissions(@PathVariable UUID id,
                                  @RequestParam(name = "codes", required = false) List<String> codes,
                                  @ModelAttribute("currentBranch") BranchView branch,
                                  Model model,
                                  RedirectAttributes redirect) {
        var role = roles.findById(id).orElseThrow(() -> new RoleService.RoleNotFoundException(id));
        try {
            boolean changed = roles.setPermissions(id, codes == null ? List.of() : codes, branch);
            redirect.addFlashAttribute("flashSuccess", changed
                    ? "Permissions saved. Each holder gets them on their next page."
                    : "No permissions changed.");
            return "redirect:/admin/roles/" + id;
        } catch (AccessChangeRefusedException e) {
            // Show what was attempted, so the conflict can be seen and fixed.
            model.addAttribute("refusal", e.getMessage());
            populatePermissions(role, new HashSet<>(codes == null ? List.of() : codes), model);
            return "admin/roles/permissions";
        }
    }

    // ---- state -----------------------------------------------------------

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable UUID id,
                             @RequestParam(required = false) String reason,
                             @ModelAttribute("currentBranch") BranchView branch,
                             RedirectAttributes redirect) {
        try {
            roles.deactivate(id, reason, branch);
            redirect.addFlashAttribute("flashSuccess",
                    "Role deactivated. It grants nothing from each holder's next page; their assignments stay on record.");
        } catch (AccessChangeRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/roles/" + id;
    }

    @PostMapping("/{id}/reactivate")
    public String reactivate(@PathVariable UUID id,
                             @ModelAttribute("currentBranch") BranchView branch,
                             RedirectAttributes redirect) {
        try {
            roles.reactivate(id, branch);
            redirect.addFlashAttribute("flashSuccess", "Role reactivated. Its holders get its permissions again.");
        } catch (AccessChangeRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/roles/" + id;
    }

    // ---- helpers ---------------------------------------------------------

    private void populatePermissions(RoleRow role, Set<String> selected, Model model) {
        model.addAttribute("role", role);
        model.addAttribute("groups", roles.catalogue());
        model.addAttribute("selected", selected);
        model.addAttribute("heldByMe", roles.heldByCurrentUser(role.id()));
        model.addAttribute("holderCount", role.holderCount());
    }
}
