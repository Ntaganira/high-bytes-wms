package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : TransferController.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Transfer screens: lists, new, view, edit, submit, sign, cancel and the dispatch gate
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.inventory.lookup.StockAt;
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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The transfer screens.
 *
 * <p>The list page carries three lists for three roles at a branch: every
 * transfer raised here; "ready to dispatch" (approved, for those who load at
 * the gate); and "awaiting receipt" (dispatched to this branch, for those who
 * unload). Every refusal, by this application or by a database control
 * (including one raised only at commit), comes back as its reason on the page.
 */
@Controller
@RequestMapping("/transfers")
@PreAuthorize("hasAuthority('transfer.view')")
public class TransferController {

    private static final List<String> STATES =
            List.of("DRAFT", "PENDING", "APPROVED", "DISPATCHED", "IN_TRANSIT", "RECEIVED", "WRITTEN_OFF", "REJECTED", "CANCELLED");

    private final TransferService transfers;
    private final TransferLookupService lookups;
    private final AuditService audit;
    private final DocumentService documents;

    public TransferController(TransferService transfers, TransferLookupService lookups, AuditService audit,
                              DocumentService documents) {
        this.transfers = transfers;
        this.lookups = lookups;
        this.audit = audit;
        this.documents = documents;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "transfers";
    }

    // ---- lists ---------------------------------------------------------------

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "false") boolean awaitingMe,
                       Model model) {
        String wanted = status != null && STATES.contains(status) ? status : null;
        boolean dispatcher = CurrentUser.holdsAt("transfer.dispatch", branch.id());
        boolean receiver = CurrentUser.holdsAt("transfer.receive", branch.id());
        model.addAttribute("transfers", transfers.list(branch.id(), wanted, awaitingMe));
        model.addAttribute("ready", dispatcher ? transfers.readyToDispatch(branch.id()) : List.of());
        model.addAttribute("awaiting", receiver ? transfers.awaitingReceipt(branch.id()) : List.of());
        model.addAttribute("canDispatch", dispatcher);
        model.addAttribute("canReceive", receiver);
        model.addAttribute("statuses", STATES);
        model.addAttribute("status", wanted);
        model.addAttribute("awaitingMe", awaitingMe);
        return "transfers/list";
    }

    // ---- create ----------------------------------------------------------------

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('transfer.create')")
    public String newForm(@ModelAttribute("currentBranch") BranchView branch,
                          @RequestParam(required = false) UUID correctionOf,
                          Model model) {
        TransferForm form;
        if (correctionOf != null) {
            form = transfers.formFor(correctionOf);
            form.setId(null);
            form.setVersion(null);
            form.setSupersedesDocumentId(correctionOf);
        } else {
            form = new TransferForm();
            form.getLines().add(new TransferLineForm());
        }
        var sources = lookups.sourceLocations(branch.id());
        if (form.getFromLocationId() == null && sources.size() == 1) {
            form.setFromLocationId(sources.get(0).id());
        }
        model.addAttribute("form", form);
        addLookups(model, form, branch.id());
        return "transfers/form";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('transfer.create')")
    public String create(@Valid @ModelAttribute("form") TransferForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {
        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                UUID id = transfers.create(form);
                redirect.addFlashAttribute("flashSuccess",
                        "Transfer raised as a draft. Submit it for signature when it is ready.");
                return "redirect:/transfers/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        addLookups(model, form, branch.id());
        return "transfers/form";
    }

    // ---- view --------------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var detail = transfers.detail(id);
        model.addAttribute("trf", detail.header());
        model.addAttribute("lines", detail.lines());
        model.addAttribute("stock", detail.stock());
        model.addAttribute("available", totals(detail.stock()));
        model.addAttribute("chain", detail.chain());
        model.addAttribute("chainInfo", detail.chainInfo());
        model.addAttribute("gate", detail.gate());
        model.addAttribute("actions", detail.actions());
        model.addAttribute("receipts", detail.receipts());
        model.addAttribute("losses", detail.losses());
        model.addAttribute("posting", detail.posting());
        model.addAttribute("history", audit.historyOf("document", id, 40));
        return "transfers/view";
    }

    // ---- edit ------------------------------------------------------------------------

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('transfer.create')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        var header = transfers.find(id);
        if (!"DRAFT".equals(header.status())) {
            redirect.addFlashAttribute("flashError", header.serialNo() + " is " + header.displayState()
                    + ", so it can no longer be edited. The signers signed what it says.");
            return "redirect:/transfers/" + id;
        }
        var form = transfers.formFor(id);
        model.addAttribute("form", form);
        model.addAttribute("trf", header);
        addLookups(model, form, header.branchId());
        return "transfers/form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('transfer.create')")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") TransferForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        var header = transfers.find(id);
        form.setId(id);
        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                transfers.update(id, form);
                redirect.addFlashAttribute("flashSuccess", header.serialNo() + " saved.");
                return "redirect:/transfers/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        model.addAttribute("trf", header);
        addLookups(model, form, header.branchId());
        return "transfers/form";
    }

    // ---- line rows (htmx): the server changes the list and numbers the rows -------------

    @PostMapping("/lines/add")
    @PreAuthorize("hasAuthority('transfer.create')")
    public String addLine(@ModelAttribute("form") TransferForm form, BindingResult ignored,
                          @ModelAttribute("currentBranch") BranchView branch, Model model) {
        form.getLines().add(new TransferLineForm());
        addLookups(model, form, branch.id());
        return "transfers/form :: lines";
    }

    @PostMapping("/lines/remove/{index}")
    @PreAuthorize("hasAuthority('transfer.create')")
    public String removeLine(@PathVariable int index,
                             @ModelAttribute("form") TransferForm form, BindingResult ignored,
                             @ModelAttribute("currentBranch") BranchView branch, Model model) {
        if (index >= 0 && index < form.getLines().size() && form.getLines().size() > 1) {
            form.getLines().remove(index);
        }
        addLookups(model, form, branch.id());
        return "transfers/form :: lines";
    }

    /** The source location changed: the bins offered are its own, and a bin from the old one is cleared. */
    @PostMapping("/lines/refresh")
    @PreAuthorize("hasAuthority('transfer.create')")
    public String refreshLines(@ModelAttribute("form") TransferForm form, BindingResult ignored,
                               @ModelAttribute("currentBranch") BranchView branch, Model model) {
        var bins = lookups.binsOf(form.getFromLocationId()).stream()
                .map(b -> b.id()).collect(java.util.stream.Collectors.toSet());
        for (TransferLineForm line : form.getLines()) {
            if (line.getStorageBinId() != null && !bins.contains(line.getStorageBinId())) {
                line.setStorageBinId(null);
            }
        }
        addLookups(model, form, branch.id());
        return "transfers/form :: lines";
    }

    // ---- lifecycle ---------------------------------------------------------------------

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyAuthority('transfer.create','transfer.approve','transfer.verify')")
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Submit", redirect, () -> "APPROVED".equals(transfers.submit(id))
                ? "Submitted, and every step has signed: the transfer is approved and may be dispatched."
                : "Submitted. You signed step 1; the transfer now awaits the next signature.");
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('transfer.create','transfer.approve','transfer.verify')")
    public String approve(@PathVariable UUID id,
                          @RequestParam(required = false) String comment,
                          RedirectAttributes redirect) {
        return attempt(id, "Approve", redirect, () -> "APPROVED".equals(transfers.sign(id, true, comment))
                ? "Signed. That was the last step: the transfer is approved and the warehouse may dispatch it."
                : "Signed. The transfer moves to the next step.");
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyAuthority('transfer.create','transfer.approve','transfer.verify')")
    public String reject(@PathVariable UUID id,
                         @RequestParam(required = false) String comment,
                         RedirectAttributes redirect) {
        return attempt(id, "Reject", redirect, () -> {
            transfers.sign(id, false, comment);
            return "Rejected. A rejected transfer is final: raise a corrected copy from it.";
        });
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('transfer.create')")
    public String cancel(@PathVariable UUID id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        return attempt(id, "Cancel", redirect, () -> {
            transfers.cancel(id, reason);
            return "Cancelled. The serial stays on the register with your reason.";
        });
    }

    // ---- the gate: dispatch --------------------------------------------------------------

    @GetMapping("/{id}/dispatch")
    @PreAuthorize("hasAuthority('transfer.dispatch')")
    public String dispatchScreen(@PathVariable UUID id, Model model) {
        var detail = transfers.detail(id);
        model.addAttribute("trf", detail.header());
        model.addAttribute("lines", detail.lines());
        model.addAttribute("stock", detail.stock());
        model.addAttribute("available", totals(detail.stock()));
        model.addAttribute("gate", detail.gate());
        model.addAttribute("actions", detail.actions());
        return "transfers/dispatch";
    }

    /** Confirm dispatch at the gate. The confirmation is required by the server, not only by the button. */
    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAuthority('transfer.dispatch')")
    public String dispatch(@PathVariable UUID id,
                           @RequestParam(defaultValue = "false") boolean confirm,
                           RedirectAttributes redirect) {
        if (!confirm) {
            redirect.addFlashAttribute("flashError",
                    "Dispatch was not confirmed. Tick the confirmation to let the goods leave: it cannot be undone.");
            return "redirect:/transfers/" + id + "/dispatch";
        }
        return attempt(id, "Dispatch", redirect,
                () -> "Dispatched into transit under ticket " + transfers.dispatch(id) + ".");
    }

    // ---- helpers ---------------------------------------------------------------------------

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
        return "redirect:/transfers/" + id;
    }

    /** Rules that need more than one field, so the user sees a field error before the database's refusal. */
    private void validateCrossFields(TransferForm form, BindingResult binding) {
        if (form.getCustomsReference() == null && lookups.touchesBonded(form.getFromLocationId(), form.getToLocationId())) {
            binding.rejectValue("customsReference", "customsRequired",
                    "Bonded stock needs a customs reference: it is accountable to Customs on the move.");
        }
    }

    private void addLookups(Model model, TransferForm form, UUID branchId) {
        model.addAttribute("sources", lookups.sourceLocations(branchId));
        model.addAttribute("destinations", lookups.destinations(branchId));
        model.addAttribute("units", lookups.units());
        model.addAttribute("items", lookups.items());
        model.addAttribute("bins", lookups.binsOf(form.getFromLocationId()));
        model.addAttribute("bondedHere", lookups.touchesBonded(form.getFromLocationId(), form.getToLocationId()));
    }

    private static Map<UUID, BigDecimal> totals(Map<UUID, List<StockAt>> stock) {
        Map<UUID, BigDecimal> totals = new HashMap<>();
        stock.forEach((item, places) -> totals.put(item, places.stream()
                .map(StockAt::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
        return totals;
    }
}
