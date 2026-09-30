package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DeliveryNoteController.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Delivery note screens: ready to load, list, raise, edit, view, cancel and confirm release at the gate
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.BinOption;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The gate.
 *
 * <p>"Ready to load" lists what the warehouse may load now: released
 * authorizations with no live note. A note starts from one of those, lines
 * prefilled at the authorized quantities; the user splits a line across bins
 * (htmx, numbered by the server), enters vehicle, driver and the thickness
 * measured at the gate, and confirms release, which posts. The release
 * banner is on the form and the view, so whoever is at the screen sees
 * whether the goods may leave.
 */
@Controller
@RequestMapping("/delivery-notes")
@PreAuthorize("hasAuthority('dispatch.view')")
public class DeliveryNoteController {

    private static final List<String> STATUSES = List.of("DRAFT", "POSTED", "CANCELLED");

    private final DeliveryNoteService notes;
    private final AuditService audit;
    private final DocumentService documents;

    public DeliveryNoteController(DeliveryNoteService notes, AuditService audit, DocumentService documents) {
        this.notes = notes;
        this.audit = audit;
        this.documents = documents;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "deliveryNotes";
    }

    // ---- list and ready to load ------------------------------------------------

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) String status,
                       Model model) {
        String wanted = status != null && STATUSES.contains(status) ? status : null;
        boolean poster = heritier.ntaganira.highbytes.wms.security.CurrentUser.holdsAt("dispatch.post", branch.id());
        model.addAttribute("ready", poster ? notes.readyToLoad(branch.id()) : List.of());
        model.addAttribute("canLoad", poster);
        model.addAttribute("notes", notes.list(branch.id(), wanted));
        model.addAttribute("statuses", STATUSES);
        model.addAttribute("status", wanted);
        return "delivery-notes/list";
    }

    // ---- raise -------------------------------------------------------------------

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('dispatch.post')")
    public String newForm(@RequestParam UUID dao, Model model) {
        var form = notes.prefill(dao);
        model.addAttribute("form", form);
        addContext(model, form);
        return "delivery-notes/form";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('dispatch.post')")
    public String create(@Valid @ModelAttribute("form") DnForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        var context = addContext(model, form);
        validateCrossFields(form, binding, context);
        if (!binding.hasErrors()) {
            try {
                UUID id = notes.create(form);
                redirect.addFlashAttribute("flashSuccess",
                        "Delivery note raised as a draft. Check the load, then confirm release at the gate.");
                return "redirect:/delivery-notes/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        return "delivery-notes/form";
    }

    // ---- view --------------------------------------------------------------------

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var detail = notes.detail(id);
        model.addAttribute("dn", detail.header());
        model.addAttribute("lines", detail.lines());
        model.addAttribute("gate", detail.gate());
        model.addAttribute("stock", detail.stock());
        model.addAttribute("available", totals(detail.stock()));
        model.addAttribute("actions", detail.actions());
        model.addAttribute("posting", detail.posting());
        model.addAttribute("history", audit.historyOf("document", id, 40));
        return "delivery-notes/view";
    }

    // ---- edit --------------------------------------------------------------------

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('dispatch.post')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        var header = notes.find(id);
        if (!"DRAFT".equals(header.status())) {
            redirect.addFlashAttribute("flashError", header.serialNo() + " is " + header.status()
                    + ", so it can no longer be edited.");
            return "redirect:/delivery-notes/" + id;
        }
        var form = notes.formFor(id);
        model.addAttribute("form", form);
        model.addAttribute("dn", header);
        addContext(model, form);
        return "delivery-notes/form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('dispatch.post')")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") DnForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        var header = notes.find(id);
        form.setId(id);
        form.setAuthorizationId(header.authorizationId());
        var context = addContext(model, form);
        validateCrossFields(form, binding, context);
        if (!binding.hasErrors()) {
            try {
                notes.update(id, form);
                redirect.addFlashAttribute("flashSuccess", header.serialNo() + " saved.");
                return "redirect:/delivery-notes/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        model.addAttribute("dn", header);
        return "delivery-notes/form";
    }

    // ---- line rows (htmx): the server splits or removes a row and numbers the rest ----

    /** Splits a line: a new row for the same authorization line, quantity blank, to be given another bin. */
    @PostMapping("/lines/split/{index}")
    @PreAuthorize("hasAuthority('dispatch.post')")
    public String splitLine(@PathVariable int index,
                            @ModelAttribute("form") DnForm form, BindingResult ignored, Model model) {
        if (index >= 0 && index < form.getLines().size()) {
            var source = form.getLines().get(index);
            var part = new DnLineForm();
            part.setAuthorizationLineId(source.getAuthorizationLineId());
            part.setMeasuredThicknessMm(source.getMeasuredThicknessMm());
            form.getLines().add(index + 1, part);
        }
        addContext(model, form);
        return "delivery-notes/form :: lines";
    }

    /** Removes a split row, never the last one serving an authorization line. */
    @PostMapping("/lines/remove/{index}")
    @PreAuthorize("hasAuthority('dispatch.post')")
    public String removeLine(@PathVariable int index,
                             @ModelAttribute("form") DnForm form, BindingResult ignored, Model model) {
        if (index >= 0 && index < form.getLines().size()) {
            UUID serves = form.getLines().get(index).getAuthorizationLineId();
            long sharing = form.getLines().stream()
                    .filter(l -> serves != null && serves.equals(l.getAuthorizationLineId())).count();
            if (sharing > 1) {
                form.getLines().remove(index);
            }
        }
        addContext(model, form);
        return "delivery-notes/form :: lines";
    }

    // ---- lifecycle -----------------------------------------------------------------

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('dispatch.post')")
    public String cancel(@PathVariable UUID id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        return attempt(id, "Cancel", redirect, () -> {
            notes.cancel(id, reason);
            return "Cancelled. The authorization is free to be loaded again.";
        });
    }

    /** Confirm release at the gate. The confirmation is required by the server, not only by the button. */
    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('dispatch.post')")
    public String post(@PathVariable UUID id,
                       @RequestParam(defaultValue = "false") boolean confirm,
                       RedirectAttributes redirect) {
        if (!confirm) {
            redirect.addFlashAttribute("flashError",
                    "Release was not confirmed. Tick the confirmation to let the goods leave: it cannot be undone.");
            return "redirect:/delivery-notes/" + id;
        }
        return attempt(id, "Post", redirect,
                () -> "Released at the gate. The stock has left the ledger under ticket " + notes.post(id) + ".");
    }

    // ---- helpers ---------------------------------------------------------------------

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
        return "redirect:/delivery-notes/" + id;
    }

    /** The authorization, gate, stock and bins the form draws beside the lines. */
    private DeliveryNoteService.Context addContext(Model model, DnForm form) {
        var context = notes.contextFor(form.getAuthorizationId());
        model.addAttribute("dao", context.dao());
        Map<UUID, DaoLineRow> byId = new LinkedHashMap<>();
        context.daoLines().forEach(l -> byId.put(l.id(), l));
        model.addAttribute("authLines", byId);
        model.addAttribute("gate", context.gate());
        model.addAttribute("stock", context.stock());
        model.addAttribute("available", totals(context.stock()));
        model.addAttribute("bins", context.bins());
        // Loadable now: released and no other live note. (A note being edited is its own live note.)
        model.addAttribute("loadable", context.gate().released()
                && (context.gate().dnId() == null || context.gate().dnId().equals(form.getId())));
        return context;
    }

    private static Map<UUID, BigDecimal> totals(Map<UUID, List<StockAt>> stock) {
        Map<UUID, BigDecimal> totals = new HashMap<>();
        stock.forEach((item, places) -> totals.put(item,
                places.stream().map(StockAt::quantity).reduce(BigDecimal.ZERO, BigDecimal::add)));
        return totals;
    }

    /**
     * Rules that need more than one field, so the user sees a field error
     * before the database's refusal: the parts of a split line add up to what
     * was authorized, glass carries its measured thickness, and a bin is in
     * the location goods leave from.
     */
    private void validateCrossFields(DnForm form, BindingResult binding, DeliveryNoteService.Context context) {
        Map<UUID, DaoLineRow> byId = new HashMap<>();
        context.daoLines().forEach(l -> byId.put(l.id(), l));
        Set<UUID> binIds = context.bins().stream().map(BinOption::id).collect(Collectors.toSet());

        Map<UUID, BigDecimal> loaded = new HashMap<>();
        Map<UUID, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < form.getLines().size(); i++) {
            DnLineForm line = form.getLines().get(i);
            DaoLineRow serves = byId.get(line.getAuthorizationLineId());
            if (serves == null) {
                if (!binding.hasFieldErrors("lines[" + i + "].authorizationLineId")) {
                    binding.rejectValue("lines[" + i + "].authorizationLineId", "unknownLine",
                            "This line does not serve a line of the authorization.");
                }
                continue;
            }
            firstIndex.putIfAbsent(serves.id(), i);
            if (line.getQuantity() != null) loaded.merge(serves.id(), line.getQuantity(), BigDecimal::add);
            if (serves.glass() && line.getMeasuredThicknessMm() == null
                    && !binding.hasFieldErrors("lines[" + i + "].measuredThicknessMm")) {
                binding.rejectValue("lines[" + i + "].measuredThicknessMm", "glassThickness",
                        "Glass needs its thickness measured at the gate (nominal "
                        + (serves.nominalThicknessMm() == null ? "not recorded" : serves.nominalThicknessMm() + " mm") + ").");
            }
            if (line.getStorageBinId() != null && !binIds.contains(line.getStorageBinId())) {
                binding.rejectValue("lines[" + i + "].storageBinId", "binElsewhere",
                        "This bin is not in " + context.dao().locationCode() + ", where the goods leave from.");
            }
        }
        for (DaoLineRow authorized : context.daoLines()) {
            BigDecimal got = loaded.getOrDefault(authorized.id(), BigDecimal.ZERO);
            Integer at = firstIndex.get(authorized.id());
            if (got.compareTo(authorized.quantity()) != 0) {
                String message = "Authorization line " + authorized.lineNo() + " allows "
                        + authorized.quantity().stripTrailingZeros().toPlainString() + " " + authorized.uomCode()
                        + " and this note loads " + got.stripTrailingZeros().toPlainString()
                        + ". The load must equal the authorization exactly; a line split across bins must add up.";
                if (at != null && !binding.hasFieldErrors("lines[" + at + "].quantity")) {
                    binding.rejectValue("lines[" + at + "].quantity", "loadMismatch", message);
                } else if (at == null) {
                    binding.reject("loadMismatch", message);
                }
            }
        }
    }
}
