package heritier.ntaganira.highbytes.wms.masterdata.item;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.item
 * - File       : ItemController.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Item master screens: list, create, view, edit, deactivate and reactivate
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/items")
@PreAuthorize("hasAuthority('item.view')")
public class ItemController {

    private final ItemService items;
    private final ItemLookupService lookups;
    private final AuditService audit;

    public ItemController(ItemService items, ItemLookupService lookups, AuditService audit) {
        this.items = items;
        this.lookups = lookups;
        this.audit = audit;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "items";
    }

    @ModelAttribute("productTypes")
    public ProductType[] productTypes() {
        return ProductType.values();
    }

    // ---- list ------------------------------------------------------------

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) ProductType type,
                       @RequestParam(defaultValue = "false") boolean includeInactive,
                       @RequestParam(defaultValue = "false") boolean belowReorder,
                       Model model) {

        model.addAttribute("items", items.search(
                branch == null ? null : branch.id(), q, type, includeInactive, belowReorder));
        model.addAttribute("q", q);
        model.addAttribute("type", type);
        model.addAttribute("includeInactive", includeInactive);
        model.addAttribute("belowReorder", belowReorder);
        return "masterdata/items/list";
    }

    // ---- create ----------------------------------------------------------

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('item.manage')")
    public String newForm(Model model) {
        var form = new ItemForm();
        form.setProductType(ProductType.GLASS);
        form.setBaseUomId(lookups.defaultUomForGlass());
        model.addAttribute("form", form);
        addLookups(model);
        return "masterdata/items/form";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('item.manage')")
    public String create(@Valid @ModelAttribute("form") ItemForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {

        validateCrossFields(form, binding);
        if (binding.hasErrors()) {
            addLookups(model);
            return "masterdata/items/form";
        }

        try {
            UUID id = items.create(form, branch);
            redirect.addFlashAttribute("flashSuccess",
                    "Item " + form.getItemCode() + " created.");
            return "redirect:/items/" + id;
        } catch (ItemService.ItemCodeTakenException e) {
            binding.rejectValue("itemCode", "duplicate", e.getMessage());
            addLookups(model);
            return "masterdata/items/form";
        }
    }

    // ---- view ------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id,
                       @ModelAttribute("currentBranch") BranchView branch,
                       Model model) {

        var item = items.findById(id, branch == null ? null : branch.id())
                .orElseThrow(() -> new ItemNotFoundException(id));

        model.addAttribute("item", item);
        model.addAttribute("transacted", items.hasBeenTransacted(id));
        model.addAttribute("history", audit.historyOf("item", id, 20));
        return "masterdata/items/view";
    }

    // ---- edit ------------------------------------------------------------

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('item.manage')")
    public String editForm(@PathVariable UUID id, Model model) {
        var form = items.formFor(id);
        form.setTransacted(items.hasBeenTransacted(id));
        model.addAttribute("form", form);
        addLookups(model);
        return "masterdata/items/form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('item.manage')")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") ItemForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {

        validateCrossFields(form, binding);
        if (binding.hasErrors()) {
            form.setTransacted(items.hasBeenTransacted(id));
            addLookups(model);
            return "masterdata/items/form";
        }

        try {
            items.update(id, form, branch);
            redirect.addFlashAttribute("flashSuccess", "Item " + form.getItemCode() + " updated.");
            return "redirect:/items/" + id;
        } catch (ItemService.ItemCodeTakenException e) {
            binding.rejectValue("itemCode", "duplicate", e.getMessage());
        } catch (ItemService.ItemSpecificationLockedException e) {
            binding.rejectValue("thicknessMm", "locked", e.getMessage());
        }
        form.setTransacted(items.hasBeenTransacted(id));
        addLookups(model);
        return "masterdata/items/form";
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('item.manage')")
    public String deactivate(@PathVariable UUID id,
                             @RequestParam(required = false) String reason,
                             @ModelAttribute("currentBranch") BranchView branch,
                             RedirectAttributes redirect) {
        items.setActive(id, false, reason, branch);
        redirect.addFlashAttribute("flashSuccess",
                "Item deactivated. It stays in the ledger and on past documents.");
        return "redirect:/items/" + id;
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('item.manage')")
    public String reactivate(@PathVariable UUID id,
                             @ModelAttribute("currentBranch") BranchView branch,
                             RedirectAttributes redirect) {
        items.setActive(id, true, null, branch);
        redirect.addFlashAttribute("flashSuccess", "Item reactivated.");
        return "redirect:/items/" + id;
    }

    // ---- helpers ---------------------------------------------------------

    /** Rules that need more than one field, which bean validation cannot express. */
    private void validateCrossFields(ItemForm form, BindingResult binding) {
        if (!form.isThicknessPresentWhenGlass()) {
            binding.addError(new FieldError("form", "thicknessMm",
                    "Glass must carry a thickness: it is measured at the gate on every movement."));
        }
        if (!form.isStockLevelsConsistent()) {
            binding.addError(new FieldError("form", "maxStockLevel",
                    "The maximum level cannot be below the reorder level."));
        }
    }

    private void addLookups(Model model) {
        model.addAttribute("units", lookups.units());
        model.addAttribute("categories", lookups.categories());
    }

    @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    static class ItemNotFoundException extends RuntimeException {
        ItemNotFoundException(UUID id) {
            super("No item with id " + id);
        }
    }
}
