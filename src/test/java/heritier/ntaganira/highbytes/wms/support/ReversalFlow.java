package heritier.ntaganira.highbytes.wms.support;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.support
 * - File       : ReversalFlow.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Walks a reversing document through its chain, each step as its own person, at one branch
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.reversal.ReversalForm;
import heritier.ntaganira.highbytes.wms.inventory.reversal.ReversalService;

import java.util.UUID;

/**
 * The proposed chain's four people, none of whom raised or posted anything
 * else: a Warehouse Manager raises, the Internal Controller verifies, the
 * Managing Director approves, Finance posts. Each holds the role at the
 * branch of the documents it reverses. Every call goes through the service,
 * so every control the application applies is applied here too.
 */
public class ReversalFlow {

    public static final String REASON = "Keyed against the wrong delivery: the goods on it never arrived.";

    public final Fixtures fx;
    public final ReversalService reversals;
    public final UUID branch;

    public final UUID raiser;
    public final UUID verifier;
    public final UUID approver;
    public final UUID poster;

    public ReversalFlow(Fixtures fx, ReversalService reversals, String branchCode) {
        this.fx = fx;
        this.reversals = reversals;
        this.branch = fx.branch(branchCode);
        this.raiser = fx.userAt(branchCode, "revwm", "WH_MANAGER");
        this.verifier = fx.userAt(branchCode, "revic", "INTERNAL_CTRL");
        this.approver = fx.userAt(branchCode, "revmd", "MANAGING_DIR");
        this.poster = fx.userAt(branchCode, "revfin", "FINANCE");
    }

    public ReversalFlow(Fixtures fx, ReversalService reversals) {
        this(fx, reversals, "KGL");
    }

    public ReversalForm form(UUID original) {
        ReversalForm form = new ReversalForm();
        form.setOriginalId(original);
        form.setReason(REASON);
        return form;
    }

    public UUID draft(UUID original) {
        fx.actAs(raiser, branch);
        return reversals.create(form(original));
    }

    public UUID pending(UUID original) {
        UUID id = draft(original);
        fx.actAs(raiser, branch);
        reversals.submit(id);
        return id;
    }

    public UUID approved(UUID original) {
        UUID id = pending(original);
        fx.actAs(verifier, branch);
        reversals.sign(id, true, null);
        fx.actAs(approver, branch);
        reversals.sign(id, true, null);
        return id;
    }

    /** Finance posts; returns the serials of the mirror tickets. */
    public String post(UUID id) {
        fx.actAs(poster, branch);
        return reversals.post(id);
    }

    /** Raised, signed and posted: the original undone. Returns the reversal. */
    public UUID reverse(UUID original) {
        UUID id = approved(original);
        post(id);
        return id;
    }
}
