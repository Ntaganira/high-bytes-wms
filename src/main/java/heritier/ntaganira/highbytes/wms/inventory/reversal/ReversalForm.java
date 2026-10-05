package heritier.ntaganira.highbytes.wms.inventory.reversal;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.reversal
 * - File       : ReversalForm.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Raising or editing a reversal: the document it undoes and why
 * </pre>
 */

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Two fields, deliberately. What the reversal moves is not entered: it is
 * every movement of the original, mirrored, so there is nothing to key and
 * nothing to get wrong. What is entered is the reason, which is what the
 * Internal Controller and the Managing Director judge.
 */
public class ReversalForm {

    private UUID id;
    private Integer version;

    @NotNull(message = "Choose the posted document to reverse")
    private UUID originalId;

    @NotBlank(message = "Say what was wrong: the signers judge the reversal by its reason")
    @Size(min = 15, max = 600, message = "Say what was wrong in 15 to 600 characters")
    private String reason;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    /** Raising a new reversal rather than editing a draft's reason. */
    public boolean isNew() { return id == null; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public UUID getOriginalId() { return originalId; }
    public void setOriginalId(UUID originalId) { this.originalId = originalId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason == null ? null : reason.strip(); }
}
