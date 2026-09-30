package heritier.ntaganira.highbytes.wms.masterdata.supplier;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.supplier
 * - File       : SupplierController.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Supplier master screens: list, create, view, edit, deactivate and reactivate
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
 * Suppliers. Everything here needs {@code partner.manage}: setting up who the
 * company buys from, and so who a receipt may name, is Finance's, not the
 * store's.
 */
@Controller
@RequestMapping("/suppliers")
@PreAuthorize("hasAuthority('partner.manage')")
public class SupplierController {

    private final SupplierService suppliers;
    private final AuditService audit;

    public SupplierController(SupplierService suppliers, AuditService audit) {
        this.suppliers = suppliers;
        this.audit = audit;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "suppliers";
    }

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "false") boolean includeInactive,
                       Model model) {
        model.addAttribute("suppliers", suppliers.search(q, includeInactive));
        model.addAttribute("q", q);
        model.addAttribute("includeInactive", includeInactive);
        return "masterdata/suppliers/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new SupplierForm());
        return "masterdata/suppliers/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") SupplierForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return "masterdata/suppliers/form";
        }
        try {
            UUID id = suppliers.create(form, branch);
            redirect.addFlashAttribute("flashSuccess", "Supplier " + form.getCode() + " created.");
            return "redirect:/suppliers/" + id;
        } catch (SupplierService.SupplierCodeTakenException e) {
            binding.rejectValue("code", "duplicate", e.getMessage());
            return "masterdata/suppliers/form";
        }
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var supplier = suppliers.findById(id).orElseThrow(() -> new SupplierService.SupplierNotFoundException(id));
        model.addAttribute("supplier", supplier);
        model.addAttribute("history", audit.historyOf("supplier", id, 20));
        return "masterdata/suppliers/view";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        var form = suppliers.formFor(id);
        model.addAttribute("form", form);
        model.addAttribute("storedCode", form.getCode());
        return "masterdata/suppliers/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") SupplierForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {
        // Before validation, so an invalid form for a missing supplier is a 404 too.
        model.addAttribute("storedCode", suppliers.formFor(id).getCode());
        form.setId(id);
        if (binding.hasErrors()) {
            return "masterdata/suppliers/form";
        }
        try {
            suppliers.update(id, form, branch);
            redirect.addFlashAttribute("flashSuccess", "Supplier " + form.getCode() + " updated.");
            return "redirect:/suppliers/" + id;
        } catch (SupplierService.SupplierCodeTakenException e) {
            binding.rejectValue("code", "duplicate", e.getMessage());
            return "masterdata/suppliers/form";
        }
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable UUID id,
                             @RequestParam(required = false) String reason,
                             @ModelAttribute("currentBranch") BranchView branch,
                             RedirectAttributes redirect) {
        suppliers.setActive(id, false, reason, branch);
        redirect.addFlashAttribute("flashSuccess",
                "Supplier deactivated. It stays on past receipts, and no new receipt can name it.");
        return "redirect:/suppliers/" + id;
    }

    @PostMapping("/{id}/reactivate")
    public String reactivate(@PathVariable UUID id,
                             @ModelAttribute("currentBranch") BranchView branch,
                             RedirectAttributes redirect) {
        suppliers.setActive(id, true, null, branch);
        redirect.addFlashAttribute("flashSuccess", "Supplier reactivated.");
        return "redirect:/suppliers/" + id;
    }
}
