package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DispatchController.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Delivery authorization screens: list, new, view, edit, submit, sign, cancel, line rows
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.inventory.lookup.StockAt;
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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The delivery authorization screens.
 *
 * <p>Every refusal, by this application or by a database control (including
 * one raised only when the transaction tries to commit), comes back as its
 * reason on the page, never a stack trace. What the viewer sees offered is a
 * convenience; the service methods authorise.
 */
@Controller
@RequestMapping("/dispatch")
@PreAuthorize("hasAuthority('dispatch.view')")
public class DispatchController {

    private static final List<String> STATES =
            List.of("DRAFT", "PENDING", "RELEASED", "DELIVERED", "REJECTED", "CANCELLED");

    private final DispatchService dispatch;
    private final DispatchLookupService lookups;
    private final AuditService audit;
    private final DocumentService documents;

    public DispatchController(DispatchService dispatch, DispatchLookupService lookups, AuditService audit,
                              DocumentService documents) {
        this.dispatch = dispatch;
        this.lookups = lookups;
        this.audit = audit;
        this.documents = documents;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "dispatch";
    }

    // ---- list ------------------------------------------------------------

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "false") boolean awaitingMe,
                       Model model) {
        String wanted = status != null && STATES.contains(status) ? status : null;
        model.addAttribute("notes", branch == null ? List.of() : dispatch.list(branch.id(), wanted, awaitingMe));
        model.addAttribute("statuses", STATES);
        model.addAttribute("status", wanted);
        model.addAttribute("awaitingMe", awaitingMe);
        return "dispatch/list";
    }

    // ---- create ----------------------------------------------------------

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('dispatch.create')")
    public String newForm(@ModelAttribute("currentBranch") BranchView branch,
                          @RequestParam(required = false) UUID correctionOf,
                          Model model) {
        DaoForm form;
        if (correctionOf != null) {
            form = dispatch.formFor(correctionOf);
            form.setId(null);
            form.setVersion(null);
            form.setSupersedesDocumentId(correctionOf);
        } else {
            form = new DaoForm();
            form.getLines().add(new DaoLineForm());
        }
        var locations = lookups.dispatchLocations(branch.id());
        if (form.getLocationId() == null && locations.size() == 1) {
            form.setLocationId(locations.get(0).id());
        }
        model.addAttribute("form", form);
        addLookups(model, form, branch.id());
        return "dispatch/form";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('dispatch.create')")
    public String create(@Valid @ModelAttribute("form") DaoForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {
        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                UUID id = dispatch.create(form);
                redirect.addFlashAttribute("flashSuccess",
                        "Delivery authorization raised as a draft. Submit it for signature when it is ready.");
                return "redirect:/dispatch/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        addLookups(model, form, branch.id());
        return "dispatch/form";
    }

    // ---- view ------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var detail = dispatch.detail(id);
        model.addAttribute("dao", detail.header());
        model.addAttribute("lines", detail.lines());
        model.addAttribute("stock", detail.stock());
        Map<UUID, BigDecimal> available = new HashMap<>();
        detail.stock().forEach((item, places) -> available.put(item,
                places.stream().map(StockAt::quantity).reduce(BigDecimal.ZERO, BigDecimal::add)));
        model.addAttribute("available", available);
        model.addAttribute("chain", detail.chain());
        model.addAttribute("chainInfo", detail.chainInfo());
        model.addAttribute("gate", detail.gate());
        model.addAttribute("actions", detail.actions());
        model.addAttribute("history", audit.historyOf("document", id, 40));
        return "dispatch/view";
    }

    // ---- edit ------------------------------------------------------------

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('dispatch.create')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        var header = dispatch.find(id);
        if (!"DRAFT".equals(header.status())) {
            redirect.addFlashAttribute("flashError", header.serialNo() + " is " + header.displayState()
                    + ", so it can no longer be edited. The signers signed what it says.");
            return "redirect:/dispatch/" + id;
        }
        var form = dispatch.formFor(id);
        model.addAttribute("form", form);
        model.addAttribute("dao", header);
        addLookups(model, form, header.branchId());
        return "dispatch/form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch.create')")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") DaoForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        var header = dispatch.find(id);
        form.setId(id);
        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                dispatch.update(id, form);
                redirect.addFlashAttribute("flashSuccess", header.serialNo() + " saved.");
                return "redirect:/dispatch/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        model.addAttribute("dao", header);
        addLookups(model, form, header.branchId());
        return "dispatch/form";
    }

    // ---- line rows (htmx): the server changes the list and numbers the rows --------

    @PostMapping("/lines/add")
    @PreAuthorize("hasAuthority('dispatch.create')")
    public String addLine(@ModelAttribute("form") DaoForm form, BindingResult ignored,
                          @ModelAttribute("currentBranch") BranchView branch, Model model) {
        form.getLines().add(new DaoLineForm());
        addLookups(model, form, branch.id());
        return "dispatch/form :: lines";
    }

    @PostMapping("/lines/remove/{index}")
    @PreAuthorize("hasAuthority('dispatch.create')")
    public String removeLine(@PathVariable int index,
                             @ModelAttribute("form") DaoForm form, BindingResult ignored,
                             @ModelAttribute("currentBranch") BranchView branch, Model model) {
        if (index >= 0 && index < form.getLines().size() && form.getLines().size() > 1) {
            form.getLines().remove(index);
        }
        addLookups(model, form, branch.id());
        return "dispatch/form :: lines";
    }

    // ---- lifecycle ---------------------------------------------------------

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyAuthority('dispatch.create','dispatch.verify','dispatch.countersign','dispatch.release')")
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Submit", redirect, () -> {
            String status = dispatch.submit(id);
            return "APPROVED".equals(status)
                    ? "Submitted, and every step has signed: the authorization is released."
                    : "Submitted. You signed step 1; the authorization now awaits the next signature.";
        });
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('dispatch.create','dispatch.verify','dispatch.countersign','dispatch.release')")
    public String approve(@PathVariable UUID id,
                          @RequestParam(required = false) String comment,
                          RedirectAttributes redirect) {
        return attempt(id, "Approve", redirect, () -> "APPROVED".equals(dispatch.sign(id, true, comment))
                ? "Signed. That was the last step: the authorization is released and the warehouse may load."
                : "Signed. The authorization moves to the next step.");
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyAuthority('dispatch.create','dispatch.verify','dispatch.countersign','dispatch.release')")
    public String reject(@PathVariable UUID id,
                         @RequestParam(required = false) String comment,
                         RedirectAttributes redirect) {
        return attempt(id, "Reject", redirect, () -> {
            dispatch.sign(id, false, comment);
            return "Rejected. A rejected authorization is final: raise a corrected copy from it.";
        });
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('dispatch.create')")
    public String cancel(@PathVariable UUID id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        return attempt(id, "Cancel", redirect, () -> {
            dispatch.cancel(id, reason);
            return "Cancelled. The serial stays on the register with your reason.";
        });
    }

    // ---- helpers ---------------------------------------------------------

    /** Runs a lifecycle action; a refusal, at the statement or at commit, comes back as its reason on the page. */
    private String attempt(UUID id, String what, RedirectAttributes redirect, java.util.function.Supplier<String> action) {
        try {
            redirect.addFlashAttribute("flashSuccess", action.get());
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (RuntimeException e) {
            var refusal = documents.refusedAtCommit(id, what, e).orElseThrow(() -> e);
            redirect.addFlashAttribute("flashError", refusal.getMessage());
        }
        return "redirect:/dispatch/" + id;
    }

    /** Rules that need more than one field, so the user sees a field error before the database's refusal. */
    private void validateCrossFields(DaoForm form, BindingResult binding) {
        if (form.getLocationId() != null && form.getCustomsReference() == null
                && lookups.releasesBonded(form.getLocationId())) {
            binding.rejectValue("customsReference", "customsRequired",
                    "Bonded stock needs a customs reference: it is accountable to Customs when it leaves.");
        }
    }

    private void addLookups(Model model, DaoForm form, UUID branchId) {
        model.addAttribute("customers", lookups.customers());
        model.addAttribute("locations", lookups.dispatchLocations(branchId));
        model.addAttribute("units", lookups.units());
        model.addAttribute("items", lookups.items());
        model.addAttribute("bondedHere", form.getLocationId() != null && lookups.releasesBonded(form.getLocationId()));
    }
}
