package heritier.ntaganira.highbytes.wms.approval;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.approval
 * - File       : ApprovalQueueController.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The approval queue screen: what waits on the reader's signature, at every branch they sign at
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.DocumentKind;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * {@code /approvals}, read-only. Each row links to the document's own page, where it is read and signed. A
 * document at another branch is signed from that branch's screens, so its row offers the switch when the reader's
 * rights here would not reach it. Signed in is enough to open it (SecurityConfig): it is the reader's own, and
 * its query judges every row by the reader's rights at that row's branch.
 */
@Controller
@RequestMapping("/approvals")
public class ApprovalQueueController {

    static final int LIMIT = 200;

    /** The types a chain is signed on; a transaction ticket has none. */
    static final List<DocumentKind> SIGNED_KINDS = Arrays.stream(DocumentKind.values())
            .filter(k -> k != DocumentKind.TT)
            .toList();

    private final ApprovalQueueService queue;

    public ApprovalQueueController(ApprovalQueueService queue) {
        this.queue = queue;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "approvals";
    }

    @GetMapping
    public String queue(@RequestParam(required = false) UUID branch,
                        @RequestParam(required = false) String type,
                        Model model) {
        DocumentKind kind = SIGNED_KINDS.stream().filter(k -> k.code().equals(type)).findFirst().orElse(null);
        var rows = queue.awaiting(branch, kind, LIMIT);
        model.addAttribute("rows", rows);
        model.addAttribute("truncated", rows.size() >= LIMIT);
        model.addAttribute("overdue", rows.stream().filter(AwaitingSignature::overdue).count());
        model.addAttribute("kinds", SIGNED_KINDS);
        model.addAttribute("branch", branch);
        model.addAttribute("type", kind == null ? null : kind.code());
        model.addAttribute("filtered", branch != null || kind != null);
        return "approvals/queue";
    }
}
