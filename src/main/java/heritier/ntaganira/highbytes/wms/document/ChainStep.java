package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : ChainStep.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One step of a document's bound approval chain and where it stands
 * </pre>
 */

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One step of the chain a document is bound to, with its state.
 *
 * <p>{@code state} is what {@code fragments/ui :: approvalChain} draws:
 * {@code signed}, {@code rejected}, {@code current} (the next step to sign,
 * while the document is still open) or {@code pending} (not yet reached).
 * The role name of a signed step is the label captured when it was signed,
 * so a role renamed later does not rewrite history.
 *
 * <p>The getters exist because the shared fragment reads bean properties.
 */
public record ChainStep(
        UUID stepId,
        int sequenceNo,
        String actionLabel,
        boolean mandatory,
        UUID roleId,
        String roleCode,
        String roleName,
        String decision,
        UUID actorUserId,
        String actorName,
        LocalDateTime decidedAt,
        String comment,
        String state
) {

    public boolean signed()  { return "APPROVED".equals(decision); }
    public boolean unsigned() { return decision == null; }

    public String getState()          { return state; }
    public String getRoleName()       { return roleName; }
    public String getActionLabel()    { return actionLabel; }
    public String getActorName()      { return actorName; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public String getComment()        { return comment; }
    public int getSequenceNo()        { return sequenceNo; }
}
