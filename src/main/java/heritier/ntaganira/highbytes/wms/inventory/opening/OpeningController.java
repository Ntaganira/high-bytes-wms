package heritier.ntaganira.highbytes.wms.inventory.opening;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.opening
 * - File       : OpeningController.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Opening stock balance screens: list, new, view, edit, submit, sign, cancel, post, line rows
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.inventory.lookup.BinOption;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The opening stock balance screens: the cutover from QuickBooks.
 *
 * <p>Every refusal, by this application or by a database control, comes back
 * as its reason on the page (a flash on the view, an error box on the form),
 * never as a stack trace. What the viewer sees offered is a convenience; the
 * service methods are what authorise.
 *
 * <p>Pickers come from this module's own {@link OpeningLookupService}, asked
 * under {@code opening.view}, so reading the cutover form never depends on
 * holding a receiving right. The option types it returns are the shared ones
 * in {@code inventory.lookup}.
 */
@Controller
@RequestMapping("/opening")
@PreAuthorize("hasAuthority('opening.view')")
public class OpeningController {

    private static final List<String> STATUSES =
            List.of("DRAFT", "PENDING", "APPROVED", "POSTED", "REJECTED", "CANCELLED");

    private final OpeningService opening;
    private final OpeningLookupService lookups;
    private final AuditService audit;
    private final DocumentService documents;

    public OpeningController(OpeningService opening, OpeningLookupService lookups, AuditService audit,
                             DocumentService documents) {
        this.opening = opening;
        this.lookups = lookups;
        this.audit = audit;
        this.documents = documents;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "opening";
    }

    // ---- list ------------------------------------------------------------

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "false") boolean awaitingMe,
                       Model model) {
        String wanted = status != null && STATUSES.contains(status) ? status : null;
        model.addAttribute("sheets", branch == null ? List.of()
                : opening.list(branch.id(), wanted, awaitingMe));
        model.addAttribute("statuses", STATUSES);
        model.addAttribute("status", wanted);
        model.addAttribute("awaitingMe", awaitingMe);
        return "opening/list";
    }

    // ---- create ----------------------------------------------------------

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('opening.create')")
    public String newForm(@ModelAttribute("currentBranch") BranchView branch, Model model) {
        OpeningForm form = new OpeningForm();
        form.getLines().add(new OpeningLineForm());
        var locations = lookups.loadableLocations(branch.id());
        if (locations.size() == 1) {
            form.setLocationId(locations.get(0).id());
        }
        model.addAttribute("form", form);
        addLookups(model, form, branch.id());
        return "opening/form";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('opening.create')")
    public String create(@Valid @ModelAttribute("form") OpeningForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {
        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                UUID id = opening.create(form);
                redirect.addFlashAttribute("flashSuccess",
                        "Opening balance raised as a draft. Submit it when the count has been checked against the"
                        + " old books.");
                return "redirect:/opening/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                // A refusal raised at COMMIT (a deferred trigger) arrives here, outside the service.
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        addLookups(model, form, branch.id());
        return "opening/form";
    }

    // ---- view ------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var detail = opening.detail(id);
        model.addAttribute("opb", detail.header());
        model.addAttribute("reversedBy", documents.reversedBy(id).orElse(null));
        model.addAttribute("lines", detail.lines());
        model.addAttribute("chain", detail.chain());
        model.addAttribute("chainInfo", detail.chainInfo());
        model.addAttribute("actions", detail.actions());
        model.addAttribute("valueRwf", detail.valueRwf());
        model.addAttribute("history", audit.historyOf("document", id, 40));
        return "opening/view";
    }

    // ---- edit ------------------------------------------------------------

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('opening.create')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        var header = opening.find(id);
        if (!"DRAFT".equals(header.status())) {
            redirect.addFlashAttribute("flashError", header.serialNo() + " is " + header.status()
                    + ", so it can no longer be edited. The signatories attested to what it says.");
            return "redirect:/opening/" + id;
        }
        var form = opening.formFor(id);
        model.addAttribute("form", form);
        model.addAttribute("opb", header);
        addLookups(model, form, header.branchId());
        return "opening/form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('opening.create')")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") OpeningForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        var header = opening.find(id);
        form.setId(id);
        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                opening.update(id, form);
                redirect.addFlashAttribute("flashSuccess", header.serialNo() + " saved.");
                return "redirect:/opening/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        model.addAttribute("opb", header);
        addLookups(model, form, header.branchId());
        return "opening/form";
    }

    // ---- line rows (htmx) --------------------------------------------------
    //
    // The browser posts the whole form; the server changes the list and sends
    // back the rows, numbered from the list as it now stands. Nothing is
    // renumbered in JavaScript.

    @PostMapping("/lines/add")
    @PreAuthorize("hasAuthority('opening.create')")
    public String addLine(@ModelAttribute("form") OpeningForm form, BindingResult ignored,
                          @ModelAttribute("currentBranch") BranchView branch, Model model) {
        form.getLines().add(new OpeningLineForm());
        addLookups(model, form, branch.id());
        return "opening/form :: lines";
    }

    @PostMapping("/lines/remove/{index}")
    @PreAuthorize("hasAuthority('opening.create')")
    public String removeLine(@PathVariable int index,
                             @ModelAttribute("form") OpeningForm form, BindingResult ignored,
                             @ModelAttribute("currentBranch") BranchView branch, Model model) {
        if (index >= 0 && index < form.getLines().size() && form.getLines().size() > 1) {
            form.getLines().remove(index);
        }
        addLookups(model, form, branch.id());
        return "opening/form :: lines";
    }

    /** The place changed: the bins offered are the new one's, and a bin from the old is cleared. */
    @PostMapping("/lines/refresh")
    @PreAuthorize("hasAuthority('opening.create')")
    public String refreshLines(@ModelAttribute("form") OpeningForm form, BindingResult ignored,
                               @ModelAttribute("currentBranch") BranchView branch, Model model) {
        var bins = lookups.binsOf(form.getLocationId()).stream()
                .map(BinOption::id).collect(Collectors.toSet());
        for (OpeningLineForm line : form.getLines()) {
            if (line.getStorageBinId() != null && !bins.contains(line.getStorageBinId())) {
                line.setStorageBinId(null);
            }
        }
        addLookups(model, form, branch.id());
        return "opening/form :: lines";
    }

    // ---- lifecycle ---------------------------------------------------------

    @PostMapping("/{id}/submit")
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Submit", redirect, () -> {
            String status = opening.submit(id);
            return "APPROVED".equals(status)
                    ? "Submitted, and every step has signed: the opening balance awaits posting by Finance."
                    : "Submitted. You signed step 1; the sheet now awaits the next signature.";
        });
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable UUID id,
                          @RequestParam(required = false) String comment,
                          RedirectAttributes redirect) {
        return attempt(id, "Approve", redirect, () -> "APPROVED".equals(opening.sign(id, true, comment))
                ? "Signed. That was the last step: the opening balance awaits posting by Finance."
                : "Signed. The sheet moves to the next step.");
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable UUID id,
                         @RequestParam(required = false) String comment,
                         RedirectAttributes redirect) {
        return attempt(id, "Reject", redirect, () -> {
            opening.sign(id, false, comment);
            return "Rejected. A rejected sheet is final: raise a fresh one once the figures are agreed.";
        });
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable UUID id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        return attempt(id, "Cancel", redirect, () -> {
            opening.cancel(id, reason);
            return "Cancelled. The serial stays on the register with your reason.";
        });
    }

    @PostMapping("/{id}/post")
    public String post(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Post", redirect,
                () -> "Posted to the ledger under ticket " + opening.post(id)
                      + ". This place now has its opening balance, and will not take another.");
    }

    // ---- helpers ---------------------------------------------------------

    /** Runs a lifecycle action; a refusal comes back as its reason on the sheet's page. */
    private String attempt(UUID id, String what, RedirectAttributes redirect,
                           java.util.function.Supplier<String> action) {
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
        return "redirect:/opening/" + id;
    }

    /**
     * Rules that need more than one field, so the user sees a field error
     * before the database's refusal — and, for the cutover, before keying
     * three hundred lines into a place that cannot take them.
     */
    private void validateCrossFields(OpeningForm form, BindingResult binding) {
        if (form.getLocationId() != null && form.getCustomsReference() == null
                && opening.loadsBonded(form.getLocationId())) {
            binding.rejectValue("customsReference", "customsRequired",
                    "Bonded stock needs a customs reference: it is accountable to Customs from the moment the"
                    + " system says it is there.");
        }
        if (form.getId() == null && form.getLocationId() != null) {
            var obstacle = opening.obstacleAt(form.getLocationId());
            if (obstacle.blocked()) {
                binding.rejectValue("locationId", "cannotLoad", obstacle.reason());
            }
        }

        Set<UUID> itemIds = form.getLines().stream().map(OpeningLineForm::getItemId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> types = lookups.productTypes(itemIds);
        for (int i = 0; i < form.getLines().size(); i++) {
            OpeningLineForm line = form.getLines().get(i);
            if (line.getItemId() != null && "GLASS".equals(types.get(line.getItemId()))
                    && line.getMeasuredThicknessMm() == null
                    && !binding.hasFieldErrors("lines[" + i + "].measuredThicknessMm")) {
                binding.rejectValue("lines[" + i + "].measuredThicknessMm", "glassThickness",
                        "Glass needs its thickness measured on the cutover count: what leaves the gate later is"
                        + " checked against it.");
            }
        }
    }

    private void addLookups(Model model, OpeningForm form, UUID branchId) {
        model.addAttribute("locations", lookups.loadableLocations(branchId));
        model.addAttribute("units", lookups.units());
        model.addAttribute("items", lookups.items());
        model.addAttribute("bins", lookups.binsOf(form.getLocationId()));
        model.addAttribute("bondedHere",
                form.getLocationId() != null && opening.loadsBonded(form.getLocationId()));
        model.addAttribute("obstacle", opening.obstacleAt(form.getLocationId()));
    }
}
