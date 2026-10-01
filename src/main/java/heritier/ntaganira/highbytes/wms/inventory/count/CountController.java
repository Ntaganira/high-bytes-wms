package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : CountController.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Stock count screens: list, open, view, the counting sheet, the verification sheet, sign, cancel, post
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
import java.util.function.Supplier;

/**
 * The stock count screens.
 *
 * <p>Three pages show a count's lines, and each is built for one reader: the view
 * (anyone with {@code count.view}) shows only progress until the verification is
 * signed; the counting sheet (count.enter, while counting) shows the first count
 * and never the book; the verification sheet (the verification step's signer,
 * while it awaits that step) shows the verifier's own recount and neither the book
 * nor the first count. The book is not hidden on these pages: it is not in them.
 * Every refusal, by this application or by a database control (including one
 * raised only at commit), comes back as its reason on the page.
 */
@Controller
@RequestMapping("/counts")
@PreAuthorize("hasAuthority('count.view')")
public class CountController {

    private static final List<String> STATES =
            List.of("DRAFT", "PENDING", "APPROVED", "POSTED", "REJECTED", "CANCELLED");

    private final CountService counts;
    private final CountLookupService lookups;
    private final AuditService audit;
    private final DocumentService documents;

    public CountController(CountService counts, CountLookupService lookups, AuditService audit,
                           DocumentService documents) {
        this.counts = counts;
        this.lookups = lookups;
        this.audit = audit;
        this.documents = documents;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "counts";
    }

    // ---- list ------------------------------------------------------------------------------

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "false") boolean awaitingMe,
                       Model model) {
        String wanted = status != null && STATES.contains(status) ? status : null;
        model.addAttribute("counts", counts.list(branch.id(), wanted, awaitingMe));
        model.addAttribute("statuses", STATES);
        model.addAttribute("status", wanted);
        model.addAttribute("awaitingMe", awaitingMe);
        return "counts/list";
    }

    // ---- open --------------------------------------------------------------------------------

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('count.create')")
    public String newForm(@ModelAttribute("currentBranch") BranchView branch,
                          @RequestParam(required = false) UUID location,
                          Model model) {
        CountForm form = new CountForm();
        var locations = lookups.countableLocations(branch.id());
        if (location != null && locations.stream().anyMatch(l -> l.id().equals(location))) {
            form.setLocationId(location);
        } else if (locations.size() == 1) {
            form.setLocationId(locations.get(0).id());
        }
        model.addAttribute("form", form);
        addLookups(model, branch.id());
        return "counts/new";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('count.create')")
    public String create(@Valid @ModelAttribute("form") CountForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {
        if (form.getScope() == CountScope.PARTIAL && form.getItemIds().stream().allMatch(java.util.Objects::isNull)) {
            binding.rejectValue("itemIds", "itemsRequired", "Choose the items a cycle count covers.");
        }
        if (form.getCustomsReference() == null && form.getLocationId() != null
                && lookups.bonded(branch.id(), form.getLocationId())) {
            binding.rejectValue("customsReference", "customsRequired",
                    "Bonded stock needs a customs reference: a count may adjust duty-suspended goods.");
        }
        if (!binding.hasErrors()) {
            try {
                UUID id = counts.create(form);
                redirect.addFlashAttribute("flashSuccess", "Count opened. What it counts is frozen at the location "
                        + "until its verification is signed: count it now, then submit it.");
                return "redirect:/counts/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        addLookups(model, branch.id());
        return "counts/new";
    }

    // ---- view --------------------------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var detail = counts.detail(id);
        model.addAttribute("cnt", detail.header());
        model.addAttribute("lines", detail.lines());
        model.addAttribute("progress", detail.progress());
        model.addAttribute("chain", detail.chain());
        model.addAttribute("chainInfo", detail.chainInfo());
        model.addAttribute("gate", detail.gate());
        model.addAttribute("actions", detail.actions());
        model.addAttribute("posting", detail.posting());
        model.addAttribute("inFlight", detail.inFlight());
        model.addAttribute("history", audit.historyOf("document", id, 40));
        return "counts/view";
    }

    // ---- the counting sheet ---------------------------------------------------------------------

    @GetMapping("/{id}/count")
    @PreAuthorize("hasAuthority('count.enter')")
    public String countingSheet(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        CountService.Sheet sheet;
        try {
            sheet = counts.countingSheet(id);
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/counts/" + id;
        }
        showSheet(model, sheet, sheet.form());
        model.addAttribute("found", new FoundLineForm());
        model.addAttribute("items", lookups.items());
        model.addAttribute("bins", lookups.binsOf(sheet.header().locationId(), sheet.header().branchId()));
        return "counts/count";
    }

    @PostMapping("/{id}/count")
    @PreAuthorize("hasAuthority('count.enter')")
    public String saveCounts(@PathVariable UUID id, @ModelAttribute("form") CountEntryForm form,
                             RedirectAttributes redirect) {
        try {
            int saved = counts.saveCounts(id, form);
            redirect.addFlashAttribute("flashSuccess", saved == 0 ? "Nothing had changed, so nothing was saved."
                    : saved + (saved == 1 ? " line saved." : " lines saved."));
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("flashError", DbRefusal.reason(e).orElseThrow(() -> e));
        }
        return "redirect:/counts/" + id + "/count";
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize("hasAuthority('count.enter')")
    public String addFoundLine(@PathVariable UUID id, @Valid @ModelAttribute("found") FoundLineForm found,
                               BindingResult binding, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            redirect.addFlashAttribute("flashError", binding.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/counts/" + id + "/count";
        }
        try {
            redirect.addFlashAttribute("flashSuccess", foundMessage(counts.addFoundLine(id, found)));
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("flashError", DbRefusal.reason(e).orElseThrow(() -> e));
        }
        return "redirect:/counts/" + id + "/count";
    }

    /** Where what was found went, and which places of the item joined the sheet with it, to be counted too. */
    static String foundMessage(CountService.Found f) {
        String message = f.onBook()
                ? "The book holds that item there, so it was already a place to count: what you counted is on line "
                        + f.lineNo() + "."
                : "Added as line " + f.lineNo() + ", with what you counted.";
        if (!f.placesAdded().isEmpty()) {
            String lines = f.placesAdded().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
            message += f.placesAdded().size() == 1
                    ? " The book holds the item at one other place here: it is on the sheet as line " + lines + ". Count it too."
                    : " The book holds the item at " + f.placesAdded().size() + " other places here: they are on the "
                            + "sheet as lines " + lines + ". Count them too.";
        }
        return message;
    }

    @PostMapping("/{id}/lines/{lineId}/remove")
    @PreAuthorize("hasAuthority('count.enter')")
    public String removeLine(@PathVariable UUID id, @PathVariable UUID lineId, RedirectAttributes redirect) {
        try {
            counts.removeLine(id, lineId);
            redirect.addFlashAttribute("flashSuccess", "Line removed.");
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("flashError", DbRefusal.reason(e).orElseThrow(() -> e));
        }
        return "redirect:/counts/" + id + "/count";
    }

    // ---- the verification sheet -------------------------------------------------------------------

    @GetMapping("/{id}/verify")
    @PreAuthorize("hasAuthority('count.verify')")
    public String verificationSheet(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        CountService.Sheet sheet;
        try {
            sheet = counts.verificationSheet(id);
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/counts/" + id;
        }
        showSheet(model, sheet, sheet.form());
        return "counts/verify";
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAuthority('count.verify')")
    public String saveVerification(@PathVariable UUID id, @ModelAttribute("form") CountEntryForm form,
                                   RedirectAttributes redirect) {
        try {
            int saved = counts.saveVerification(id, form);
            redirect.addFlashAttribute("flashSuccess", saved == 0 ? "Nothing had changed, so nothing was saved."
                    : saved + (saved == 1 ? " line recounted." : " lines recounted."));
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("flashError", DbRefusal.reason(e).orElseThrow(() -> e));
        }
        return "redirect:/counts/" + id + "/verify";
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyAuthority('count.create','count.verify','count.approve')")
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return attempt(id, "Submit", redirect, () -> {
            counts.submit(id);
            return "Submitted. You signed step 1 and the first count is closed. The Internal Controller now recounts "
                    + "the lines the system chose, without seeing the book or your count.";
        });
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('count.create','count.verify','count.approve')")
    public String approve(@PathVariable UUID id,
                          @RequestParam(required = false) String comment,
                          RedirectAttributes redirect) {
        return attempt(id, "Approve", redirect, () -> "APPROVED".equals(counts.sign(id, true, comment))
                ? "Signed. That was the last step: the adjustment is approved and a second Finance officer may post it."
                : "Signed. The count moves to the next step.");
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyAuthority('count.create','count.verify','count.approve')")
    public String reject(@PathVariable UUID id,
                         @RequestParam(required = false) String comment,
                         RedirectAttributes redirect) {
        return attempt(id, "Reject", redirect, () -> {
            counts.sign(id, false, comment);
            return "Rejected. A rejected count is final: open a new one to count again.";
        });
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('count.create')")
    public String cancel(@PathVariable UUID id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        return attempt(id, "Cancel", redirect, () -> {
            counts.cancel(id, reason);
            return "Cancelled. The serial stays on the register with your reason, and the location is no longer frozen.";
        });
    }

    /** Post the adjustment. The confirmation is required by the server, not only by the button. */
    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('count.post')")
    public String post(@PathVariable UUID id,
                       @RequestParam(defaultValue = "false") boolean confirm,
                       RedirectAttributes redirect) {
        if (!confirm) {
            redirect.addFlashAttribute("flashError",
                    "Posting was not confirmed. Tick the confirmation to adjust the stock: it cannot be undone.");
            return "redirect:/counts/" + id;
        }
        return attempt(id, "Post", redirect, () -> {
            List<String> tickets = counts.post(id);
            return tickets.isEmpty() ? "Posted. Every line agreed with the book, so no stock moved."
                    : "Posted to the ledger under " + String.join(" and ", tickets) + ".";
        });
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Runs a lifecycle action; a refusal, at the statement or at commit, comes back as its reason on the page. */
    private String attempt(UUID id, String what, RedirectAttributes redirect, Supplier<String> action) {
        try {
            redirect.addFlashAttribute("flashSuccess", action.get());
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (RuntimeException e) {
            var refusal = documents.refusedAtCommit(id, what, e).orElseThrow(() -> e);
            redirect.addFlashAttribute("flashError", refusal.getMessage());
        }
        return "redirect:/counts/" + id;
    }

    private void showSheet(Model model, CountService.Sheet sheet, CountEntryForm form) {
        model.addAttribute("cnt", sheet.header());
        model.addAttribute("lines", sheet.lines());
        model.addAttribute("progress", sheet.progress());
        model.addAttribute("form", form);
    }

    private void addLookups(Model model, UUID branchId) {
        model.addAttribute("locations", lookups.countableLocations(branchId));
        model.addAttribute("scopes", CountScope.values());
        model.addAttribute("items", lookups.items());
        model.addAttribute("canCreate", CurrentUser.holdsAt("count.create", branchId));
    }
}
