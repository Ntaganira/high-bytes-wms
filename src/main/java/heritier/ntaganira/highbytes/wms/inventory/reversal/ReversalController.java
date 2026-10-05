package heritier.ntaganira.highbytes.wms.inventory.reversal;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.reversal
 * - File       : ReversalController.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The reversing document screens: choose a posted document, say why, sign, post
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
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
import java.util.UUID;

/**
 * The reversal screens. Raising one is two choices, the posted document and
 * the reason; the form then shows every movement it will undo and whether
 * each place can take it today, before anything is written.
 *
 * <p>Every refusal, by this application or by a database control, comes back
 * as its reason on the page, never as a stack trace. What the viewer sees
 * offered is a convenience; the service methods are what authorise.
 */
@Controller
@RequestMapping("/reversals")
@PreAuthorize("hasAuthority('reversal.view')")
public class ReversalController {

    private static final List<String> STATUSES =
            List.of("DRAFT", "PENDING", "APPROVED", "POSTED", "REJECTED", "CANCELLED");

    private final ReversalService reversals;
    private final ReversalLookupService lookups;
    private final AuditService audit;
    private final DocumentService documents;

    public ReversalController(ReversalService reversals, ReversalLookupService lookups, AuditService audit,
                              DocumentService documents) {
        this.reversals = reversals;
        this.lookups = lookups;
        this.audit = audit;
        this.documents = documents;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "reversals";
    }

    // ---- list ------------------------------------------------------------

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "false") boolean awaitingMe,
                       Model model) {
        String wanted = status != null && STATUSES.contains(status) ? status : null;
        model.addAttribute("reversals", branch == null ? List.of()
                : reversals.list(branch.id(), wanted, awaitingMe));
        model.addAttribute("statuses", STATUSES);
        model.addAttribute("status", wanted);
        model.addAttribute("awaitingMe", awaitingMe);
        return "reversals/list";
    }

    // ---- create ----------------------------------------------------------

    /** Without a document, the picker; with one, what reversing it would do. */
    @GetMapping("/new")
    @PreAuthorize("hasAuthority('reversal.create')")
    public String newForm(@ModelAttribute("currentBranch") BranchView branch,
                          @RequestParam(required = false) UUID original,
                          Model model) {
        ReversalForm form = new ReversalForm();
        form.setOriginalId(original);
        model.addAttribute("form", form);
        addPreview(model, form, branch);
        return "reversals/form";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('reversal.create')")
    public String create(@Valid @ModelAttribute("form") ReversalForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {
        if (!binding.hasErrors()) {
            try {
                UUID id = reversals.create(form);
                redirect.addFlashAttribute("flashSuccess",
                        "Reversal raised as a draft. Submit it to send it to the Internal Controller.");
                return "redirect:/reversals/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                // A refusal raised at COMMIT (a deferred trigger) arrives here, outside the service.
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        addPreview(model, form, branch);
        return "reversals/form";
    }

    // ---- view ------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var detail = reversals.detail(id);
        model.addAttribute("rev", detail.header());
        model.addAttribute("lines", detail.lines());
        model.addAttribute("valueRwf", detail.valueRwf());
        model.addAttribute("chain", detail.chain());
        model.addAttribute("chainInfo", detail.chainInfo());
        model.addAttribute("actions", detail.actions());
        model.addAttribute("history", audit.historyOf("document", id, 40));
        return "reversals/view";
    }

    // ---- edit ------------------------------------------------------------

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('reversal.create')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        var header = reversals.find(id);
        if (!"DRAFT".equals(header.status())) {
            redirect.addFlashAttribute("flashError", header.serialNo() + " is " + header.status()
                    + ", so its reason can no longer change. The signers judged what it says.");
            return "redirect:/reversals/" + id;
        }
        CurrentUser.requireAt("reversal.create", header.branchId());
        ReversalForm form = new ReversalForm();
        form.setId(id);
        form.setVersion(header.version());
        form.setOriginalId(header.originalId());
        form.setReason(header.reason());
        model.addAttribute("form", form);
        model.addAttribute("rev", header);
        model.addAttribute("preview", reversals.preview(header.originalId()));
        return "reversals/form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('reversal.create')")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") ReversalForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        var header = reversals.find(id);
        form.setId(id);
        if (!binding.hasErrors()) {
            try {
                reversals.update(id, form);
                redirect.addFlashAttribute("flashSuccess", header.serialNo() + " saved.");
                return "redirect:/reversals/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        model.addAttribute("rev", header);
        model.addAttribute("preview", reversals.preview(header.originalId()));
        return "reversals/form";
    }

    // ---- lifecycle ---------------------------------------------------------

    @PostMapping("/{id}/submit")
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Submit", redirect, () -> {
            reversals.submit(id);
            return "Submitted. You signed step 1; the reversal now awaits the Internal Controller.";
        });
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable UUID id,
                          @RequestParam(required = false) String comment,
                          RedirectAttributes redirect) {
        return attempt(id, "Approve", redirect, () -> "APPROVED".equals(reversals.sign(id, true, comment))
                ? "Signed. That was the last step: the reversal awaits posting by Finance."
                : "Signed. The reversal moves to the next step.");
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable UUID id,
                         @RequestParam(required = false) String comment,
                         RedirectAttributes redirect) {
        return attempt(id, "Reject", redirect, () -> {
            reversals.sign(id, false, comment);
            return "Rejected. The original stands as posted; a rejected reversal is final.";
        });
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable UUID id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        return attempt(id, "Cancel", redirect, () -> {
            reversals.cancel(id, reason);
            return "Cancelled. The serial stays on the register with your reason, and the original stands as posted.";
        });
    }

    @PostMapping("/{id}/post")
    public String post(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Post", redirect,
                () -> "Posted under ticket " + reversals.post(id)
                      + ". Every movement of the original is now undone in the ledger; both stay on record.");
    }

    // ---- helpers ---------------------------------------------------------

    /** Runs a lifecycle action; a refusal comes back as its reason on the reversal's page. */
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
        return "redirect:/reversals/" + id;
    }

    private void addPreview(Model model, ReversalForm form, BranchView branch) {
        if (form.getOriginalId() != null) {
            model.addAttribute("preview", reversals.preview(form.getOriginalId()));
        } else {
            model.addAttribute("candidates", branch == null ? List.of()
                    : lookups.candidates(branch.id(), CurrentUser.id()));
        }
    }
}
