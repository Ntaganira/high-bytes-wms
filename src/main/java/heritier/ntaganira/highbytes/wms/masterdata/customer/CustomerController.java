package heritier.ntaganira.highbytes.wms.masterdata.customer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.customer
 * - File       : CustomerController.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Customer master screens: list, create, view, edit, deactivate, block and unblock
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * Customers. Everything here needs {@code partner.manage}: who may be
 * delivered to, and who is blocked, is Finance's decision.
 */
@Controller
@RequestMapping("/customers")
@PreAuthorize("hasAuthority('partner.manage')")
public class CustomerController {

    private final CustomerService customers;
    private final AuditService audit;

    public CustomerController(CustomerService customers, AuditService audit) {
        this.customers = customers;
        this.audit = audit;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "customers";
    }

    @ModelAttribute("markets")
    public CustomerMarket[] markets() {
        return CustomerMarket.values();
    }

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "false") boolean includeInactive,
                       Model model) {
        model.addAttribute("customers", customers.search(q, includeInactive));
        model.addAttribute("q", q);
        model.addAttribute("includeInactive", includeInactive);
        return "masterdata/customers/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new CustomerForm());
        return "masterdata/customers/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") CustomerForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return "masterdata/customers/form";
        }
        try {
            UUID id = customers.create(form, branch);
            redirect.addFlashAttribute("flashSuccess", "Customer " + form.getCode() + " created.");
            return "redirect:/customers/" + id;
        } catch (CustomerService.CustomerCodeTakenException e) {
            binding.rejectValue("code", "duplicate", e.getMessage());
            return "masterdata/customers/form";
        }
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var customer = customers.findById(id).orElseThrow(() -> new CustomerService.CustomerNotFoundException(id));
        model.addAttribute("customer", customer);
        model.addAttribute("history", audit.historyOf("customer", id, 20));
        return "masterdata/customers/view";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        var form = customers.formFor(id);
        model.addAttribute("form", form);
        model.addAttribute("storedCode", form.getCode());
        return "masterdata/customers/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") CustomerForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {
        // Before validation, so an invalid form for a missing customer is a 404 too.
        model.addAttribute("storedCode", customers.formFor(id).getCode());
        form.setId(id);
        if (binding.hasErrors()) {
            return "masterdata/customers/form";
        }
        try {
            customers.update(id, form, branch);
            redirect.addFlashAttribute("flashSuccess", "Customer " + form.getCode() + " updated.");
            return "redirect:/customers/" + id;
        } catch (CustomerService.CustomerCodeTakenException e) {
            binding.rejectValue("code", "duplicate", e.getMessage());
            return "masterdata/customers/form";
        }
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable UUID id,
                             @RequestParam(required = false) String reason,
                             @ModelAttribute("currentBranch") BranchView branch,
                             RedirectAttributes redirect) {
        customers.setActive(id, false, reason, branch);
        redirect.addFlashAttribute("flashSuccess",
                "Customer deactivated. It stays on past documents, and nothing new can be authorized for it.");
        return "redirect:/customers/" + id;
    }

    @PostMapping("/{id}/reactivate")
    public String reactivate(@PathVariable UUID id,
                             @ModelAttribute("currentBranch") BranchView branch,
                             RedirectAttributes redirect) {
        customers.setActive(id, true, null, branch);
        redirect.addFlashAttribute("flashSuccess", "Customer reactivated.");
        return "redirect:/customers/" + id;
    }

    @PostMapping("/{id}/block")
    public String block(@PathVariable UUID id,
                        @RequestParam(required = false) String reason,
                        @ModelAttribute("currentBranch") BranchView branch,
                        RedirectAttributes redirect) {
        try {
            customers.setBlocked(id, true, reason, branch);
            redirect.addFlashAttribute("flashSuccess",
                    "Customer blocked. No new delivery can be authorized for it until Finance unblocks it.");
        } catch (CustomerService.BlockNeedsReasonException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/customers/" + id;
    }

    @PostMapping("/{id}/unblock")
    public String unblock(@PathVariable UUID id,
                          @RequestParam(required = false) String reason,
                          @ModelAttribute("currentBranch") BranchView branch,
                          RedirectAttributes redirect) {
        customers.setBlocked(id, false, reason, branch);
        redirect.addFlashAttribute("flashSuccess", "Customer unblocked.");
        return "redirect:/customers/" + id;
    }
}
