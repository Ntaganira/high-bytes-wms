package heritier.ntaganira.highbytes.wms.admin.user;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.user
 * - File       : UserController.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : User screens: list, create, view, edit, deactivate, reset password, unlock, grant and revoke roles
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.security.AccessChangeRefusedException;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
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
@RequestMapping("/admin/users")
@PreAuthorize("hasAuthority('admin.users')")
public class UserController {

    private final UserService users;
    private final BranchService branches;
    private final AuditService audit;
    private final SmartValidator validator;

    public UserController(UserService users, BranchService branches, AuditService audit,
                          jakarta.validation.Validator validator) {
        this.users = users;
        this.branches = branches;
        this.audit = audit;
        this.validator = new SpringValidatorAdapter(validator);
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "users";
    }

    // ---- list ------------------------------------------------------------

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) UUID role,
                       @RequestParam(defaultValue = "CURRENT") AccountFilter status,
                       Model model) {
        model.addAttribute("users", users.search(q, role, status));
        model.addAttribute("roles", users.grantableRoles());
        model.addAttribute("filters", AccountFilter.values());
        model.addAttribute("q", q);
        model.addAttribute("role", role);
        model.addAttribute("status", status);
        return "admin/users/list";
    }

    // ---- create ----------------------------------------------------------

    @GetMapping("/new")
    public String newForm(@ModelAttribute("currentBranch") BranchView branch, Model model) {
        var form = new UserForm();
        if (branch != null) form.setHomeBranchId(branch.id());
        model.addAttribute("form", form);
        model.addAttribute("branches", branches.findAll());
        return "admin/users/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") UserForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {

        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                var issued = users.create(form, branch);
                redirect.addFlashAttribute("flashSuccess", "Account " + form.getUsername() + " created.");
                redirect.addFlashAttribute("temporaryPassword", issued.temporaryPassword());
                return "redirect:/admin/users/" + issued.id();
            } catch (UserService.UsernameTakenException e) {
                binding.rejectValue("username", "duplicate", e.getMessage());
            } catch (UserService.UnknownBranchException e) {
                binding.rejectValue("homeBranchId", "unknown", e.getMessage());
            }
        }
        model.addAttribute("branches", branches.findAll());
        return "admin/users/form";
    }

    // ---- view ------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        model.addAttribute("grant", new GrantForm());
        populateView(id, model);
        return "admin/users/view";
    }

    // ---- edit ------------------------------------------------------------

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        if (id.equals(CurrentUser.id())) {
            redirect.addFlashAttribute("flashError",
                    "Your own account is changed by another administrator: nobody administers their own access.");
            return "redirect:/admin/users/" + id;
        }
        model.addAttribute("form", users.formFor(id));
        model.addAttribute("branches", branches.findAll());
        return "admin/users/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @ModelAttribute("form") UserForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {

        // Before validation, so an invalid form for a missing user is a 404 too.
        String username = users.formFor(id).getUsername();

        // The username never changes. Whatever was posted, the stored one is
        // validated and shown, so the heading always names the account edited.
        form.setId(id);
        form.setUsername(username);
        validator.validate(form, binding);

        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                users.update(id, form, branch);
                redirect.addFlashAttribute("flashSuccess", "Account " + username + " updated.");
                return "redirect:/admin/users/" + id;
            } catch (AccessChangeRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (UserService.UnknownBranchException e) {
                binding.rejectValue("homeBranchId", "unknown", e.getMessage());
            }
        }
        model.addAttribute("branches", branches.findAll());
        return "admin/users/form";
    }

    // ---- account state ---------------------------------------------------

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable UUID id,
                             @RequestParam(required = false) String reason,
                             @ModelAttribute("currentBranch") BranchView branch,
                             RedirectAttributes redirect) {
        try {
            users.deactivate(id, reason, branch);
            redirect.addFlashAttribute("flashSuccess",
                    "Account deactivated. Any open session ends on its next page. Its roles stay on record.");
        } catch (AccessChangeRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/{id}/reactivate")
    public String reactivate(@PathVariable UUID id,
                             @ModelAttribute("currentBranch") BranchView branch,
                             RedirectAttributes redirect) {
        try {
            users.reactivate(id, branch);
            redirect.addFlashAttribute("flashSuccess", "Account reactivated. Its unrevoked roles apply again.");
        } catch (AccessChangeRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable UUID id,
                                @ModelAttribute("currentBranch") BranchView branch,
                                RedirectAttributes redirect) {
        try {
            String temporary = users.resetPassword(id, branch);
            redirect.addFlashAttribute("flashSuccess",
                    "Password reset. The account's open sessions end on their next page.");
            redirect.addFlashAttribute("temporaryPassword", temporary);
        } catch (AccessChangeRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/{id}/unlock")
    public String unlock(@PathVariable UUID id,
                         @ModelAttribute("currentBranch") BranchView branch,
                         RedirectAttributes redirect) {
        try {
            users.unlock(id, branch);
            redirect.addFlashAttribute("flashSuccess", "Account unlocked. The user can sign in again.");
        } catch (AccessChangeRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    // ---- roles -----------------------------------------------------------

    @PostMapping("/{id}/roles")
    public String grant(@PathVariable UUID id,
                        @Valid @ModelAttribute("grant") GrantForm grant,
                        BindingResult binding,
                        @ModelAttribute("currentBranch") BranchView branch,
                        Model model,
                        RedirectAttributes redirect) {

        if (!users.exists(id)) throw new UserService.UserNotFoundException(id);

        if (!binding.hasErrors()) {
            try {
                var warnings = users.grant(id, grant, branch);
                redirect.addFlashAttribute("flashSuccess",
                        "Role granted. It applies from the user's next page on its start date.");
                if (!warnings.isEmpty()) {
                    redirect.addFlashAttribute("flashWarning",
                            "Granted despite a segregation warning: " + String.join("; ", warnings));
                }
                return "redirect:/admin/users/" + id;
            } catch (AccessChangeRefusedException e) {
                binding.reject("refused", e.getMessage());
            }
        }
        // Keep the grant form open, with what was entered and why it was refused.
        model.addAttribute("grantOpen", true);
        populateView(id, model);
        return "admin/users/view";
    }

    @PostMapping("/{id}/roles/{assignmentId}/revoke")
    public String revoke(@PathVariable UUID id,
                         @PathVariable UUID assignmentId,
                         @RequestParam(required = false) String reason,
                         @ModelAttribute("currentBranch") BranchView branch,
                         RedirectAttributes redirect) {
        try {
            users.revoke(id, assignmentId, reason, branch);
            redirect.addFlashAttribute("flashSuccess",
                    "Role revoked. It stops applying on the user's next page. The assignment stays on record.");
        } catch (AccessChangeRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    // ---- helpers ---------------------------------------------------------

    private void populateView(UUID id, Model model) {
        var user = users.detail(id).orElseThrow(() -> new UserService.UserNotFoundException(id));
        model.addAttribute("user", user);
        model.addAttribute("self", id.equals(CurrentUser.id()));
        model.addAttribute("assignments", users.assignments(id));
        model.addAttribute("held", users.heldPermissions(id));
        model.addAttribute("roles", users.grantableRoles());
        model.addAttribute("branches", branches.findAll());
        model.addAttribute("colleagues", users.colleaguesOf(id));
        model.addAttribute("history", audit.historyOf("app_user", id, 30));
        model.addAttribute("signIns", audit.historyOf("sign_in", id, 10));
    }

    /** Rules that need more than one field, which bean validation cannot express. */
    private void validateCrossFields(UserForm form, BindingResult binding) {
        if (!form.isLeaveConsistent()) {
            binding.addError(new FieldError("form", "lastLeaveEnd",
                    "Enter both dates of the leave, the end on or after the start, and a start no later than today."));
        }
    }
}
