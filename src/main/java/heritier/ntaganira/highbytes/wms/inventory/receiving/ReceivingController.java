package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : ReceivingController.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Goods received screens: list, new, view, edit, submit, sign, cancel, post, line rows
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The goods received screens.
 *
 * <p>Every refusal, by this application or by a database control, comes back
 * as its reason on the page (a flash on the view, an error box on the form),
 * never as a stack trace. What the viewer sees offered is a convenience; the
 * service methods are what authorise.
 */
@Controller
@RequestMapping("/receiving")
@PreAuthorize("hasAuthority('receiving.view')")
public class ReceivingController {

    private static final List<String> STATUSES =
            List.of("DRAFT", "PENDING", "APPROVED", "POSTED", "REJECTED", "CANCELLED");

    private final ReceivingService receiving;
    private final ReceivingLookupService lookups;
    private final AuditService audit;
    private final DocumentService documents;

    public ReceivingController(ReceivingService receiving, ReceivingLookupService lookups, AuditService audit,
                               DocumentService documents) {
        this.documents = documents;
        this.receiving = receiving;
        this.lookups = lookups;
        this.audit = audit;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "receiving";
    }

    // ---- list ------------------------------------------------------------

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "false") boolean awaitingMe,
                       Model model) {
        String wanted = status != null && STATUSES.contains(status) ? status : null;
        model.addAttribute("notes", branch == null ? List.of()
                : receiving.list(branch.id(), wanted, awaitingMe));
        model.addAttribute("statuses", STATUSES);
        model.addAttribute("status", wanted);
        model.addAttribute("awaitingMe", awaitingMe);
        return "receiving/list";
    }

    // ---- create ----------------------------------------------------------

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('receiving.create')")
    public String newForm(@ModelAttribute("currentBranch") BranchView branch,
                          @RequestParam(required = false) UUID correctionOf,
                          Model model) {
        GrnForm form;
        if (correctionOf != null) {
            // A corrected copy: the same content, a new document that names the one it corrects.
            form = receiving.formFor(correctionOf);
            form.setId(null);
            form.setVersion(null);
            form.setSupersedesDocumentId(correctionOf);
        } else {
            form = new GrnForm();
            form.getLines().add(new GrnLineForm());
        }
        var locations = lookups.receivingLocations(branch.id());
        if (form.getLocationId() == null && locations.size() == 1) {
            form.setLocationId(locations.get(0).id());
        }
        model.addAttribute("form", form);
        addLookups(model, form, branch.id());
        return "receiving/form";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('receiving.create')")
    public String create(@Valid @ModelAttribute("form") GrnForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {
        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                UUID id = receiving.create(form);
                redirect.addFlashAttribute("flashSuccess",
                        "Goods received note raised as a draft. Submit it when the receipt is checked.");
                return "redirect:/receiving/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                // A refusal raised at COMMIT (a deferred trigger) arrives here, outside the service.
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        addLookups(model, form, branch.id());
        return "receiving/form";
    }

    // ---- view ------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var detail = receiving.detail(id);
        model.addAttribute("grn", detail.header());
        model.addAttribute("lines", detail.lines());
        model.addAttribute("chain", detail.chain());
        model.addAttribute("chainInfo", detail.chainInfo());
        model.addAttribute("landed", detail.landed());
        model.addAttribute("actions", detail.actions());
        model.addAttribute("posting", detail.posting());
        model.addAttribute("history", audit.historyOf("document", id, 40));
        return "receiving/view";
    }

    // ---- edit ------------------------------------------------------------

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('receiving.create')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        var header = receiving.find(id);
        if (!"DRAFT".equals(header.status())) {
            redirect.addFlashAttribute("flashError", header.serialNo() + " is " + header.status()
                    + ", so it can no longer be edited. The approvers signed what it says.");
            return "redirect:/receiving/" + id;
        }
        var form = receiving.formFor(id);
        model.addAttribute("form", form);
        model.addAttribute("grn", header);
        addLookups(model, form, header.branchId());
        return "receiving/form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('receiving.create')")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") GrnForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        var header = receiving.find(id);
        form.setId(id);
        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                receiving.update(id, form);
                redirect.addFlashAttribute("flashSuccess", header.serialNo() + " saved.");
                return "redirect:/receiving/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                // A refusal raised at COMMIT (a deferred trigger) arrives here, outside the service.
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        model.addAttribute("grn", header);
        addLookups(model, form, header.branchId());
        return "receiving/form";
    }

    // ---- line rows (htmx) --------------------------------------------------
    //
    // The browser posts the whole form; the server changes the list and sends
    // back the rows, numbered from the list as it now stands. Nothing is
    // renumbered in JavaScript.

    @PostMapping("/lines/add")
    @PreAuthorize("hasAuthority('receiving.create')")
    public String addLine(@ModelAttribute("form") GrnForm form, BindingResult ignored,
                          @ModelAttribute("currentBranch") BranchView branch, Model model) {
        form.getLines().add(new GrnLineForm());
        addLookups(model, form, branch.id());
        return "receiving/form :: lines";
    }

    @PostMapping("/lines/remove/{index}")
    @PreAuthorize("hasAuthority('receiving.create')")
    public String removeLine(@PathVariable int index,
                             @ModelAttribute("form") GrnForm form, BindingResult ignored,
                             @ModelAttribute("currentBranch") BranchView branch, Model model) {
        if (index >= 0 && index < form.getLines().size() && form.getLines().size() > 1) {
            form.getLines().remove(index);
        }
        addLookups(model, form, branch.id());
        return "receiving/form :: lines";
    }

    /** The location changed: the bins offered are the new location's, and a bin from the old one is cleared. */
    @PostMapping("/lines/refresh")
    @PreAuthorize("hasAuthority('receiving.create')")
    public String refreshLines(@ModelAttribute("form") GrnForm form, BindingResult ignored,
                               @ModelAttribute("currentBranch") BranchView branch, Model model) {
        var bins = lookups.binsOf(form.getLocationId()).stream()
                .map(ReceivingLookupService.BinOption::id).collect(Collectors.toSet());
        for (GrnLineForm line : form.getLines()) {
            if (line.getStorageBinId() != null && !bins.contains(line.getStorageBinId())) {
                line.setStorageBinId(null);
            }
        }
        addLookups(model, form, branch.id());
        return "receiving/form :: lines";
    }

    // ---- lifecycle ---------------------------------------------------------

    @PostMapping("/{id}/submit")
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Submit", redirect, () -> {
            String status = receiving.submit(id);
            return "APPROVED".equals(status)
                    ? "Submitted, and every step has signed: the receipt is approved and awaits posting."
                    : "Submitted. You signed step 1; the note now awaits the next signature.";
        });
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable UUID id,
                          @RequestParam(required = false) String comment,
                          RedirectAttributes redirect) {
        return attempt(id, "Approve", redirect, () -> "APPROVED".equals(receiving.sign(id, true, comment))
                ? "Signed. That was the last step: the receipt is approved and awaits posting by Finance."
                : "Signed. The note moves to the next step.");
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable UUID id,
                         @RequestParam(required = false) String comment,
                         RedirectAttributes redirect) {
        return attempt(id, "Reject", redirect, () -> {
            receiving.sign(id, false, comment);
            return "Rejected. A rejected note is final: raise a corrected copy from it.";
        });
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable UUID id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        return attempt(id, "Cancel", redirect, () -> {
            receiving.cancel(id, reason);
            return "Cancelled. The serial stays on the register with your reason.";
        });
    }

    @PostMapping("/{id}/post")
    public String post(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Post", redirect, () -> "Posted to the ledger under ticket " + receiving.post(id) + ".");
    }

    // ---- helpers ---------------------------------------------------------

    /** Runs a lifecycle action; a refusal comes back as its reason on the note's page. */
    private String attempt(UUID id, String what, RedirectAttributes redirect, java.util.function.Supplier<String> action) {
        try {
            redirect.addFlashAttribute("flashSuccess", action.get());
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (RuntimeException e) {
            // A refusal raised when the transaction tried to COMMIT (a deferred trigger) leaves the service as
            // a transaction or data-access exception. The change and its success row are already rolled back;
            // the REJECT row is written now, and the database's own reason is what the user reads.
            var refusal = documents.refusedAtCommit(id, what, e).orElseThrow(() -> e);
            redirect.addFlashAttribute("flashError", refusal.getMessage());
        }
        return "redirect:/receiving/" + id;
    }

    /** Rules that need more than one field, so the user sees a field error before the database's refusal. */
    private void validateCrossFields(GrnForm form, BindingResult binding) {
        if ("RWF".equalsIgnoreCase(form.getCurrencyCode()) && form.getExchangeRate() != null
                && form.getExchangeRate().compareTo(BigDecimal.ONE) != 0 && !binding.hasFieldErrors("exchangeRate")) {
            binding.rejectValue("exchangeRate", "rwfRate",
                    "An invoice in RWF has an exchange rate of 1. Change the currency, or the rate.");
        }
        if (form.getLocationId() != null && form.getCustomsReference() == null
                && receiving.receivesBonded(form.getLocationId())) {
            binding.rejectValue("customsReference", "customsRequired",
                    "Bonded stock needs a customs reference: it is accountable to Customs from the moment it arrives.");
        }

        Set<UUID> itemIds = form.getLines().stream().map(GrnLineForm::getItemId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> types = lookups.productTypes(itemIds);
        for (int i = 0; i < form.getLines().size(); i++) {
            GrnLineForm line = form.getLines().get(i);
            if (line.getItemId() != null && "GLASS".equals(types.get(line.getItemId()))
                    && line.getMeasuredThicknessMm() == null
                    && !binding.hasFieldErrors("lines[" + i + "].measuredThicknessMm")) {
                binding.rejectValue("lines[" + i + "].measuredThicknessMm", "glassThickness",
                        "Glass needs its thickness measured with the approved device on receipt.");
            }
        }
    }

    private void addLookups(Model model, GrnForm form, UUID branchId) {
        model.addAttribute("suppliers", lookups.suppliers());
        model.addAttribute("locations", lookups.receivingLocations(branchId));
        model.addAttribute("units", lookups.units());
        model.addAttribute("items", lookups.items());
        model.addAttribute("bins", lookups.binsOf(form.getLocationId()));
        model.addAttribute("bondedHere", form.getLocationId() != null && receiving.receivesBonded(form.getLocationId()));
    }
}
