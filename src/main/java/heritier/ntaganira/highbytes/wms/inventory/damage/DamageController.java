package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : DamageController.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Return and damage screens: list, worklists, new, view, edit, submit, sign, cancel and post
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The return and damage screens.
 *
 * <p>A new report starts by choosing its kind, which the form then fits: a
 * write-off and a release name a location and item rows; a loss in transit and a
 * customer return are raised against a transfer or a delivery note, with a row per
 * line of it, prefilled at what remains in transit or could still come back and
 * editable down. Two worklists lead in: stock left in transit at this branch, and
 * stock sitting in quarantine. Every refusal, by this application or by a database
 * control (including one raised only at commit), comes back as its reason on the page.
 */
@Controller
@RequestMapping("/damage")
@PreAuthorize("hasAuthority('damage.view')")
public class DamageController {

    private static final List<String> STATES =
            List.of("DRAFT", "PENDING", "APPROVED", "POSTED", "REJECTED", "CANCELLED");

    private final DamageService reports;
    private final DamageLookupService lookups;
    private final AuditService audit;
    private final DocumentService documents;

    public DamageController(DamageService reports, DamageLookupService lookups, AuditService audit,
                            DocumentService documents) {
        this.reports = reports;
        this.lookups = lookups;
        this.audit = audit;
        this.documents = documents;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "damage";
    }

    // ---- lists and worklists -----------------------------------------------------------

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) String kind,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "false") boolean awaitingMe,
                       Model model) {
        DamageKind wantedKind = DamageKind.parse(kind);
        String wantedStatus = status != null && STATES.contains(status) ? status : null;
        model.addAttribute("reports", reports.list(branch.id(), wantedKind, wantedStatus, awaitingMe));
        model.addAttribute("kinds", DamageKind.values());
        model.addAttribute("kind", wantedKind);
        model.addAttribute("statuses", STATES);
        model.addAttribute("status", wantedStatus);
        model.addAttribute("awaitingMe", awaitingMe);
        model.addAttribute("canCreate", CurrentUser.holdsAt("damage.create", branch.id()));
        model.addAttribute("tab", "reports");
        return "damage/list";
    }

    /** Stock left in transit from this branch: dispatched and not received, or received short. */
    @GetMapping("/in-transit")
    public String leftInTransit(@ModelAttribute("currentBranch") BranchView branch, Model model) {
        model.addAttribute("rows", lookups.leftInTransit(branch.id()));
        model.addAttribute("canCreate", CurrentUser.holdsAt("damage.create", branch.id()));
        model.addAttribute("tab", "transit");
        return "damage/in-transit";
    }

    /** What sits in this branch's quarantine locations, with the two ways out. */
    @GetMapping("/quarantine")
    public String quarantine(@ModelAttribute("currentBranch") BranchView branch, Model model) {
        model.addAttribute("rows", lookups.quarantineStock(branch.id()));
        model.addAttribute("canCreate", CurrentUser.holdsAt("damage.create", branch.id()));
        model.addAttribute("tab", "quarantine");
        return "damage/quarantine";
    }

    // ---- create ---------------------------------------------------------------------------

    /**
     * Step one picks the kind; a loss or a return then picks what it is about; the form follows. The kind, and
     * the transfer or note, arrive as parameters so the worklists and the views can link straight in.
     */
    @GetMapping("/new")
    @PreAuthorize("hasAuthority('damage.create')")
    public String newForm(@ModelAttribute("currentBranch") BranchView branch,
                          @RequestParam(required = false) String kind,
                          @RequestParam(required = false) UUID transfer,
                          @RequestParam(required = false, name = "note") UUID note,
                          @RequestParam(required = false) UUID from,
                          Model model,
                          RedirectAttributes redirect) {
        DamageKind chosen = DamageKind.parse(kind);
        model.addAttribute("kinds", DamageKind.values());
        if (chosen == null) {
            return "damage/new";
        }
        DamageForm form;
        switch (chosen) {
            case TRANSIT_LOSS -> {
                if (transfer == null) {
                    model.addAttribute("kind", chosen);
                    model.addAttribute("transfers", lookups.transfersWithRemainder(branch.id()));
                    return "damage/new";
                }
                try {
                    form = reports.prefillLoss(transfer);
                } catch (ControlRefusedException e) {
                    redirect.addFlashAttribute("flashError", e.getMessage());
                    return "redirect:/transfers/" + transfer;
                }
            }
            case CUSTOMER_RETURN -> {
                if (note == null) {
                    model.addAttribute("kind", chosen);
                    model.addAttribute("notes", lookups.deliveryNotesWithReturnable(branch.id()));
                    return "damage/new";
                }
                try {
                    form = reports.prefillReturn(note);
                } catch (ControlRefusedException e) {
                    redirect.addFlashAttribute("flashError", e.getMessage());
                    return "redirect:/delivery-notes/" + note;
                }
            }
            default -> {
                form = reports.blank(chosen, branch.id());
                var sources = chosen == DamageKind.WRITE_OFF ? lookups.writeOffLocations(branch.id())
                        : lookups.quarantineLocations(branch.id());
                if (from != null && sources.stream().anyMatch(l -> l.id().equals(from))) {
                    form.setFromLocationId(from);
                } else if (sources.size() == 1) {
                    form.setFromLocationId(sources.get(0).id());
                }
            }
        }
        model.addAttribute("form", form);
        addLookups(model, form, branch.id());
        return "damage/form";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('damage.create')")
    public String create(@Valid @ModelAttribute("form") DamageForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {
        validateCrossFields(form, binding, branch.id());
        if (!binding.hasErrors()) {
            try {
                UUID id = reports.create(form);
                redirect.addFlashAttribute("flashSuccess",
                        "Report raised as a draft. Submit it for signature when it is ready.");
                return "redirect:/damage/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        addLookups(model, form, branch.id());
        return "damage/form";
    }

    // ---- view -------------------------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var detail = reports.detail(id);
        model.addAttribute("dmg", detail.header());
        model.addAttribute("reversedBy", documents.reversedBy(id).orElse(null));
        model.addAttribute("lines", detail.lines());
        model.addAttribute("chain", detail.chain());
        model.addAttribute("chainInfo", detail.chainInfo());
        model.addAttribute("gate", detail.gate());
        model.addAttribute("actions", detail.actions());
        model.addAttribute("posting", detail.posting());
        model.addAttribute("stock", detail.stock());
        model.addAttribute("history", audit.historyOf("document", id, 40));
        return "damage/view";
    }

    // ---- edit ----------------------------------------------------------------------------------

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('damage.create')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        var header = reports.find(id);
        if (!"DRAFT".equals(header.status())) {
            redirect.addFlashAttribute("flashError", header.serialNo() + " is " + header.status()
                    + ", so it can no longer be edited. The signers signed what it says.");
            return "redirect:/damage/" + id;
        }
        DamageForm form;
        try {
            form = reports.formFor(id);
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/damage/" + id;
        }
        model.addAttribute("form", form);
        model.addAttribute("dmg", header);
        addLookups(model, form, header.branchId());
        return "damage/form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('damage.create')")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") DamageForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        var header = reports.find(id);
        form.setId(id);
        form.setKind(header.kind());
        form.setTransferId(header.transferId());
        form.setDeliveryNoteId(header.deliveryNoteId());
        validateCrossFields(form, binding, header.branchId());
        if (!binding.hasErrors()) {
            try {
                reports.update(id, form);
                redirect.addFlashAttribute("flashSuccess", header.serialNo() + " saved.");
                return "redirect:/damage/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        model.addAttribute("dmg", header);
        addLookups(model, form, header.branchId());
        return "damage/form";
    }

    // ---- line rows (htmx): the server changes the list and numbers the rows ------------------------

    @PostMapping("/lines/add")
    @PreAuthorize("hasAuthority('damage.create')")
    public String addLine(@ModelAttribute("form") DamageForm form, BindingResult ignored,
                          @ModelAttribute("currentBranch") BranchView branch, Model model) {
        form.getLines().add(new DamageLineForm());
        addLookups(model, form, branch.id());
        return "damage/form :: lines";
    }

    @PostMapping("/lines/remove/{index}")
    @PreAuthorize("hasAuthority('damage.create')")
    public String removeLine(@PathVariable int index,
                             @ModelAttribute("form") DamageForm form, BindingResult ignored,
                             @ModelAttribute("currentBranch") BranchView branch, Model model) {
        if (index >= 0 && index < form.getLines().size() && form.getLines().size() > 1) {
            form.getLines().remove(index);
        }
        addLookups(model, form, branch.id());
        return "damage/form :: lines";
    }

    /** A location changed: the bins offered are its own, and a bin from another location is cleared. */
    @PostMapping("/lines/refresh")
    @PreAuthorize("hasAuthority('damage.create')")
    public String refreshLines(@ModelAttribute("form") DamageForm form, BindingResult ignored,
                               @ModelAttribute("currentBranch") BranchView branch, Model model) {
        var from = lookups.binsOf(form.getFromLocationId(), branch.id()).stream().map(b -> b.id()).collect(Collectors.toSet());
        var to = lookups.binsOf(form.getToLocationId(), branch.id()).stream().map(b -> b.id()).collect(Collectors.toSet());
        for (DamageLineForm line : form.getLines()) {
            if (line.getStorageBinId() != null && !from.contains(line.getStorageBinId())) line.setStorageBinId(null);
            if (line.getToStorageBinId() != null && !to.contains(line.getToStorageBinId())) line.setToStorageBinId(null);
        }
        addLookups(model, form, branch.id());
        return "damage/form :: lines";
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyAuthority('damage.create','damage.verify','damage.approve')")
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Submit", redirect, () -> "APPROVED".equals(reports.submit(id))
                ? "Submitted, and every step has signed: the report is approved and Finance may post it."
                : "Submitted. You signed step 1; the report now awaits the next signature.");
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('damage.create','damage.verify','damage.approve')")
    public String approve(@PathVariable UUID id,
                          @RequestParam(required = false) String comment,
                          RedirectAttributes redirect) {
        return attempt(id, "Approve", redirect, () -> "APPROVED".equals(reports.sign(id, true, comment))
                ? "Signed. That was the last step: the report is approved and Finance may post it."
                : "Signed. The report moves to the next step.");
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyAuthority('damage.create','damage.verify','damage.approve')")
    public String reject(@PathVariable UUID id,
                         @RequestParam(required = false) String comment,
                         RedirectAttributes redirect) {
        return attempt(id, "Reject", redirect, () -> {
            reports.sign(id, false, comment);
            return "Rejected. A rejected report is final: raise a new one.";
        });
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('damage.create')")
    public String cancel(@PathVariable UUID id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        return attempt(id, "Cancel", redirect, () -> {
            reports.cancel(id, reason);
            return "Cancelled. The serial stays on the register with your reason.";
        });
    }

    /** Post to the ledger. The confirmation is required by the server, not only by the button. */
    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('damage.post')")
    public String post(@PathVariable UUID id,
                       @RequestParam(defaultValue = "false") boolean confirm,
                       RedirectAttributes redirect) {
        if (!confirm) {
            redirect.addFlashAttribute("flashError",
                    "Posting was not confirmed. Tick the confirmation to move the stock: it cannot be undone.");
            return "redirect:/damage/" + id;
        }
        return attempt(id, "Post", redirect, () -> "Posted to the ledger under ticket " + reports.post(id) + ".");
    }

    // ---- helpers ----------------------------------------------------------------------------------------

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
        return "redirect:/damage/" + id;
    }

    /** Rules that need more than one field, so the user sees a field error before the database's refusal. */
    private void validateCrossFields(DamageForm form, BindingResult binding, UUID branchId) {
        DamageKind kind = form.getKind();
        if (kind == null) return;
        if (form.getReasonCode() != null && !kind.reasonCodes().contains(form.getReasonCode())) {
            binding.rejectValue("reasonCode", "reasonNotForKind",
                    "That reason does not fit a " + kind.title().toLowerCase() + ".");
        }
        if ((kind == DamageKind.WRITE_OFF || kind == DamageKind.QUARANTINE_RELEASE) && form.getFromLocationId() == null) {
            binding.rejectValue("fromLocationId", "fromRequired", kind == DamageKind.WRITE_OFF
                    ? "Choose the location the stock is written off from."
                    : "Choose the quarantine location the stock is released from.");
        }
        if (kind == DamageKind.QUARANTINE_RELEASE && form.getToLocationId() == null) {
            binding.rejectValue("toLocationId", "toRequired", "Choose the sellable location the stock goes to.");
        }
        if (form.getCustomsReference() == null
                && lookups.touchesBonded(branchId, form.getFromLocationId(), form.getToLocationId())) {
            binding.rejectValue("customsReference", "customsRequired",
                    "Bonded stock needs a customs reference: it is accountable to Customs when written off, returned or released.");
        }
        String inherited = reports.inheritedCustoms(kind, form.getTransferId(), form.getDeliveryNoteId());
        if (inherited != null && form.getCustomsReference() != null && !inherited.equals(form.getCustomsReference())) {
            binding.rejectValue("customsReference", "customsInherited",
                    "These goods moved under customs reference " + inherited + ", so the report carries that same reference.");
        }
    }

    private void addLookups(Model model, DamageForm form, UUID branchId) {
        DamageKind kind = form.getKind();
        model.addAttribute("kind", kind);
        model.addAttribute("reasonCodes", kind == null ? List.of() : kind.reasonOptions());
        model.addAttribute("bondedHere", lookups.touchesBonded(branchId, form.getFromLocationId(), form.getToLocationId()));
        model.addAttribute("inheritedCustoms",
                kind == null ? null : reports.inheritedCustoms(kind, form.getTransferId(), form.getDeliveryNoteId()));
        model.addAttribute("items", List.of());
        model.addAttribute("units", List.of());
        model.addAttribute("sources", List.of());
        model.addAttribute("destinations", List.of());
        model.addAttribute("fromBins", lookups.binsOf(form.getFromLocationId(), branchId));
        model.addAttribute("toBins", lookups.binsOf(form.getToLocationId(), branchId));
        model.addAttribute("stock", lookups.stockAt(form.getFromLocationId(), branchId));
        Map<UUID, DamageLookupService.LossLine> lossInfo = new HashMap<>();
        Map<UUID, DamageLookupService.ReturnLine> returnInfo = new HashMap<>();
        if (kind == null) return;
        switch (kind) {
            case WRITE_OFF -> {
                model.addAttribute("sources", lookups.writeOffLocations(branchId));
                model.addAttribute("items", lookups.items());
                model.addAttribute("units", lookups.units());
            }
            case QUARANTINE_RELEASE -> {
                model.addAttribute("sources", lookups.quarantineLocations(branchId));
                model.addAttribute("destinations", lookups.sellableLocations(branchId));
                model.addAttribute("items", lookups.items());
                model.addAttribute("units", lookups.units());
            }
            case TRANSIT_LOSS -> {
                model.addAttribute("subject", reports.transferSubject(form.getTransferId()));
                lookups.lossLines(form.getTransferId()).forEach(l -> lossInfo.put(l.id(), l));
            }
            case CUSTOMER_RETURN -> {
                model.addAttribute("subject", reports.noteSubject(form.getDeliveryNoteId()));
                lookups.returnLines(form.getDeliveryNoteId()).forEach(l -> returnInfo.put(l.id(), l));
                model.addAttribute("toBins", lookups.binsOf(quarantineOf(branchId), branchId));
            }
        }
        model.addAttribute("lossInfo", lossInfo);
        model.addAttribute("returnInfo", returnInfo);
    }

    /** The branch's quarantine location, where a return is received; the bins offered are its own. */
    private UUID quarantineOf(UUID branchId) {
        var quarantine = lookups.quarantineLocations(branchId);
        return quarantine.size() == 1 ? quarantine.get(0).id() : null;
    }
}
