package heritier.ntaganira.highbytes.wms.inventory.cutting;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.cutting
 * - File       : CuttingController.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The cutting order screens: list, raise, edit, view, and the chain's actions and the posting
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
import java.util.UUID;

/**
 * The cutting order screens. Every refusal, by this application or by a database control (including one raised only
 * when the transaction tries to commit), comes back as its reason on the page. What the viewer sees offered is a
 * convenience; the service methods authorise.
 */
@Controller
@RequestMapping("/cutting")
@PreAuthorize("hasAuthority('cutting.view')")
public class CuttingController {

    private static final List<String> STATES =
            List.of("DRAFT", "PENDING", "RELEASED", "POSTED", "DELIVERED", "REJECTED", "CANCELLED");

    private final CuttingService cutting;
    private final CuttingLookupService lookups;
    private final AuditService audit;
    private final DocumentService documents;

    public CuttingController(CuttingService cutting, CuttingLookupService lookups, AuditService audit,
                             DocumentService documents) {
        this.cutting = cutting;
        this.lookups = lookups;
        this.audit = audit;
        this.documents = documents;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "cutting";
    }

    // ---- list ------------------------------------------------------------

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "false") boolean awaitingMe,
                       Model model) {
        String wanted = status != null && STATES.contains(status) ? status : null;
        model.addAttribute("orders", branch == null ? List.of() : cutting.list(branch.id(), wanted, awaitingMe));
        model.addAttribute("statuses", STATES);
        model.addAttribute("status", wanted);
        model.addAttribute("awaitingMe", awaitingMe);
        return "cutting/list";
    }

    // ---- create ----------------------------------------------------------

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('cutting.create')")
    public String newForm(@ModelAttribute("currentBranch") BranchView branch,
                          @RequestParam(required = false) UUID location,
                          Model model) {
        var form = new CuttingForm();
        var places = lookups.cutLocations(branch.id());
        form.setLocationId(location != null ? location : places.isEmpty() ? null : places.get(0).id());
        form.setSheets(BigDecimal.ONE);
        form.getOutputs().add(new CuttingOutputForm());
        model.addAttribute("form", form);
        addLookups(model, form, branch.id());
        return "cutting/form";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('cutting.create')")
    public String create(@Valid @ModelAttribute("form") CuttingForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {
        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                UUID id = cutting.create(form);
                redirect.addFlashAttribute("flashSuccess",
                        "Cutting order raised as a draft. Submit it for signature when it is ready.");
                return "redirect:/cutting/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        addLookups(model, form, branch.id());
        return "cutting/form";
    }

    // ---- view ------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var detail = cutting.detail(id);
        model.addAttribute("cut", detail.header());
        model.addAttribute("sheets", detail.sheets());
        model.addAttribute("outputs", detail.outputs());
        model.addAttribute("areas", detail.areas());
        model.addAttribute("chain", detail.chain());
        model.addAttribute("chainInfo", detail.chainInfo());
        model.addAttribute("gate", detail.gate());
        model.addAttribute("actions", detail.actions());
        model.addAttribute("posting", detail.posting());
        model.addAttribute("countFreezing", detail.countFreezing());
        model.addAttribute("history", audit.historyOf("document", id, 40));
        return "cutting/view";
    }

    // ---- edit ------------------------------------------------------------

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('cutting.create')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        var header = cutting.find(id);
        if (!"DRAFT".equals(header.status())) {
            redirect.addFlashAttribute("flashError", header.serialNo() + " is " + header.displayState()
                    + ", so it can no longer be edited. The signers signed what it says.");
            return "redirect:/cutting/" + id;
        }
        var form = cutting.formFor(id);
        model.addAttribute("form", form);
        model.addAttribute("cut", header);
        addLookups(model, form, header.branchId());
        return "cutting/form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('cutting.create')")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") CuttingForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        var header = cutting.find(id);
        form.setId(id);
        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                cutting.update(id, form);
                redirect.addFlashAttribute("flashSuccess", header.serialNo() + " saved.");
                return "redirect:/cutting/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        model.addAttribute("cut", header);
        addLookups(model, form, header.branchId());
        return "cutting/form";
    }

    /** The sheet section redrawn (htmx) for the place and sheet chosen: its sheets, bins and stock are that place's. */
    @PostMapping("/sheet")
    @PreAuthorize("hasAuthority('cutting.create')")
    public String sheet(@ModelAttribute("form") CuttingForm form, BindingResult ignored,
                        @ModelAttribute("currentBranch") BranchView branch, Model model) {
        addLookups(model, form, branch.id());
        return "cutting/form :: sheet";
    }

    // ---- output rows (htmx): the server changes the list and numbers the rows --------

    @PostMapping("/outputs/add")
    @PreAuthorize("hasAuthority('cutting.create')")
    public String addOutput(@ModelAttribute("form") CuttingForm form, BindingResult ignored,
                            @ModelAttribute("currentBranch") BranchView branch, Model model) {
        form.getOutputs().add(new CuttingOutputForm());
        addLookups(model, form, branch.id());
        return "cutting/form :: outputs";
    }

    @PostMapping("/outputs/remove/{index}")
    @PreAuthorize("hasAuthority('cutting.create')")
    public String removeOutput(@PathVariable int index,
                               @ModelAttribute("form") CuttingForm form, BindingResult ignored,
                               @ModelAttribute("currentBranch") BranchView branch, Model model) {
        if (index >= 0 && index < form.getOutputs().size() && form.getOutputs().size() > 1) {
            form.getOutputs().remove(index);
        }
        addLookups(model, form, branch.id());
        return "cutting/form :: outputs";
    }

    // ---- lifecycle ---------------------------------------------------------

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyAuthority('cutting.create','cutting.verify','cutting.release')")
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Submit", redirect, () -> {
            cutting.submit(id);
            return "Submitted. You signed step 1; the order now awaits the Warehouse Manager's verification of the sizes.";
        });
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('cutting.create','cutting.verify','cutting.release')")
    public String approve(@PathVariable UUID id,
                          @RequestParam(required = false) String comment,
                          RedirectAttributes redirect) {
        return attempt(id, "Approve", redirect, () -> "APPROVED".equals(cutting.sign(id, true, comment))
                ? "Signed. That was the last step: the order is released. Once the glass is cut, a second Finance "
                  + "officer posts it."
                : "Signed. The order moves to the next step.");
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyAuthority('cutting.create','cutting.verify','cutting.release')")
    public String reject(@PathVariable UUID id,
                         @RequestParam(required = false) String comment,
                         RedirectAttributes redirect) {
        return attempt(id, "Reject", redirect, () -> {
            cutting.sign(id, false, comment);
            return "Rejected. A rejected order is final: raise a new one.";
        });
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('cutting.create')")
    public String cancel(@PathVariable UUID id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        return attempt(id, "Cancel", redirect, () -> {
            cutting.cancel(id, reason);
            return "Cancelled. The serial stays on the register with your reason.";
        });
    }

    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('cutting.post')")
    public String post(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Post", redirect, () -> "Posted: the sheets left the ledger and the pieces and off-cuts "
                + "came in, on tickets " + cutting.post(id) + ". The pieces now load at the gate on a delivery note.");
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
        return "redirect:/cutting/" + id;
    }

    /** Rules that need more than one field, so the user sees a field error before the database's refusal. */
    private void validateCrossFields(CuttingForm form, BindingResult binding) {
        if (form.getLocationId() != null && form.getCustomsReference() == null && lookups.cutsBonded(form.getLocationId())) {
            binding.rejectValue("customsReference", "customsRequired",
                    "Bonded glass needs a customs reference: it stays accountable to Customs when it is cut and when it leaves.");
        }
        for (int i = 0; i < form.getOutputs().size(); i++) {
            CuttingOutputForm o = form.getOutputs().get(i);
            if (!o.isPiece() && o.getWidthMm() != null && o.getHeightMm() != null
                    && o.getWidthMm().min(o.getHeightMm()).compareTo(MINIMUM_OFFCUT_MM) < 0) {
                binding.rejectValue("outputs[" + i + "].widthMm", "offcutTooSmall",
                        "An off-cut under 300 mm on either side is waste: it is not kept as stock.");
            }
        }
    }

    /** The smallest side an off-cut may have and be kept (V18, cut_offcut_minimum_mm). The database refuses less. */
    static final BigDecimal MINIMUM_OFFCUT_MM = BigDecimal.valueOf(300);

    private void addLookups(Model model, CuttingForm form, UUID branchId) {
        model.addAttribute("customers", lookups.customers());
        model.addAttribute("locations", lookups.cutLocations(branchId));
        model.addAttribute("sheetOptions", lookups.sheets(form.getLocationId()));
        model.addAttribute("bins", lookups.bins(form.getLocationId()));
        model.addAttribute("stock", lookups.stockAt(form.getLocationId(), form.getSheetItemId()));
        model.addAttribute("bondedHere", lookups.cutsBonded(form.getLocationId()));
    }
}
