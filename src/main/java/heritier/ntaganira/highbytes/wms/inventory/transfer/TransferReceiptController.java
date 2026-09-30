package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : TransferReceiptController.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Transfer receipt screens: raise from a dispatched transfer, edit, view, cancel and confirm
 * </pre>
 */

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The receipt screens, at the destination branch.
 *
 * <p>The form is prefilled with the dispatched quantities and is edited down
 * to what actually arrived; a row set to zero is left off, and its whole
 * quantity stays in transit. The transfer banner is on the form and the view.
 */
@Controller
@RequestMapping("/transfers/receipts")
@PreAuthorize("hasAuthority('transfer.view')")
public class TransferReceiptController {

    private final ReceiptService receipts;
    private final AuditService audit;
    private final DocumentService documents;

    public TransferReceiptController(ReceiptService receipts, AuditService audit, DocumentService documents) {
        this.receipts = receipts;
        this.audit = audit;
        this.documents = documents;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "transfers";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('transfer.receive')")
    public String newForm(@RequestParam UUID transfer, Model model, RedirectAttributes redirect) {
        ReceiptForm form;
        try {
            form = receipts.prefill(transfer);
        } catch (ControlRefusedException e) {
            // Not independent of the transfer: the form does not open, and the reason is shown where they came from.
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/transfers/" + transfer;
        }
        model.addAttribute("form", form);
        addContext(model, form);
        return "transfers/receipt-form";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('transfer.receive')")
    public String create(@Valid @ModelAttribute("form") ReceiptForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        var context = addContext(model, form);
        validateCrossFields(form, binding, context);
        if (!binding.hasErrors()) {
            try {
                UUID id = receipts.create(form);
                redirect.addFlashAttribute("flashSuccess",
                        "Receipt raised as a draft. Check what arrived, then confirm the receipt.");
                return "redirect:/transfers/receipts/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        return "transfers/receipt-form";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var detail = receipts.detail(id);
        model.addAttribute("trr", detail.header());
        model.addAttribute("lines", detail.lines());
        model.addAttribute("actions", detail.actions());
        model.addAttribute("posting", detail.posting());
        model.addAttribute("history", audit.historyOf("document", id, 40));
        return "transfers/receipt-view";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('transfer.receive')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        var header = receipts.find(id);
        if (!"DRAFT".equals(header.status())) {
            redirect.addFlashAttribute("flashError", header.serialNo() + " is " + header.status()
                    + ", so it can no longer be edited.");
            return "redirect:/transfers/receipts/" + id;
        }
        ReceiptForm form;
        try {
            form = receipts.formFor(id);
        } catch (ControlRefusedException e) {
            // A party to the transfer does not edit a bystander's receipt: the form does not open.
            redirect.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/transfers/receipts/" + id;
        }
        model.addAttribute("form", form);
        model.addAttribute("trr", header);
        addContext(model, form);
        return "transfers/receipt-form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('transfer.receive')")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") ReceiptForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirect) {
        var header = receipts.find(id);
        form.setId(id);
        form.setTransferId(header.transferId());
        var context = addContext(model, form);
        validateCrossFields(form, binding, context);
        if (!binding.hasErrors()) {
            try {
                receipts.update(id, form);
                redirect.addFlashAttribute("flashSuccess", header.serialNo() + " saved.");
                return "redirect:/transfers/receipts/" + id;
            } catch (ControlRefusedException e) {
                binding.reject("refused", e.getMessage());
            } catch (RuntimeException e) {
                binding.reject("refused", DbRefusal.reason(e).orElseThrow(() -> e));
            }
        }
        model.addAttribute("trr", header);
        return "transfers/receipt-form";
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('transfer.receive')")
    public String cancel(@PathVariable UUID id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        return attempt(id, "Cancel", redirect, () -> {
            receipts.cancel(id, reason);
            return "Cancelled. The transfer is free to be received again.";
        });
    }

    /** Confirm receipt. The confirmation is required by the server, not only by the button. */
    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('transfer.receive')")
    public String post(@PathVariable UUID id,
                       @RequestParam(defaultValue = "false") boolean confirm,
                       RedirectAttributes redirect) {
        if (!confirm) {
            redirect.addFlashAttribute("flashError",
                    "Receipt was not confirmed. Tick the confirmation to record the goods as arrived: it cannot be undone.");
            return "redirect:/transfers/receipts/" + id;
        }
        return attempt(id, "Post", redirect,
                () -> "Receipt confirmed. The goods are in stock here under ticket " + receipts.post(id) + ".");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private String attempt(UUID id, String what, RedirectAttributes redirect, java.util.function.Supplier<String> action) {
        try {
            redirect.addFlashAttribute("flashSuccess", action.get());
        } catch (ControlRefusedException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        } catch (RuntimeException e) {
            var refusal = documents.refusedAtCommit(id, what, e).orElseThrow(() -> e);
            redirect.addFlashAttribute("flashError", refusal.getMessage());
        }
        return "redirect:/transfers/receipts/" + id;
    }

    private ReceiptService.Context addContext(Model model, ReceiptForm form) {
        var context = receipts.contextFor(form.getTransferId());
        model.addAttribute("transfer", context.transfer());
        Map<UUID, TransferLineRow> byId = new LinkedHashMap<>();
        context.transferLines().forEach(l -> byId.put(l.id(), l));
        model.addAttribute("transferLines", byId);
        model.addAttribute("gate", context.gate());
        model.addAttribute("bins", context.bins());
        return context;
    }

    /**
     * What the user should see before the database's refusal: nothing more
     * arrived than was sent, at least one line arrived, and a bin is in the
     * destination location.
     */
    private void validateCrossFields(ReceiptForm form, BindingResult binding, ReceiptService.Context context) {
        Map<UUID, TransferLineRow> byId = new HashMap<>();
        context.transferLines().forEach(l -> byId.put(l.id(), l));
        Set<UUID> binIds = context.bins().stream().map(BinOption::id).collect(Collectors.toSet());
        boolean anything = false;
        for (int i = 0; i < form.getLines().size(); i++) {
            ReceiptLineForm row = form.getLines().get(i);
            TransferLineRow sent = byId.get(row.getTransferLineId());
            if (sent == null) {
                if (!binding.hasFieldErrors("lines[" + i + "].transferLineId")) {
                    binding.rejectValue("lines[" + i + "].transferLineId", "unknownLine",
                            "This row does not serve a line of the transfer.");
                }
                continue;
            }
            if (row.getQuantity() == null) continue;
            if (row.getQuantity().signum() > 0) anything = true;
            if (row.getQuantity().compareTo(sent.quantity()) > 0
                    && !binding.hasFieldErrors("lines[" + i + "].quantity")) {
                binding.rejectValue("lines[" + i + "].quantity", "overReceipt",
                        "Only " + sent.quantity().stripTrailingZeros().toPlainString() + " " + sent.uomCode()
                        + " was dispatched. More cannot arrive than was sent.");
            }
            if (row.getStorageBinId() != null && !binIds.contains(row.getStorageBinId())) {
                binding.rejectValue("lines[" + i + "].storageBinId", "binElsewhere",
                        "This bin is not in " + context.transfer().toCode() + ", where the transfer is going.");
            }
        }
        if (!anything && !binding.hasErrors()) {
            binding.reject("nothingArrived",
                    "Enter what arrived on at least one line. If nothing arrived there is nothing to receive: "
                    + "the whole consignment stays in transit until a loss report clears it.");
        }
    }
}
