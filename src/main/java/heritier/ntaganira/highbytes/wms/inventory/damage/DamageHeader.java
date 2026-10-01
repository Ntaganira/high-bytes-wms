package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : DamageHeader.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A return and damage report's header as the screens read it
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The report's header with the locations, the transfer or the delivery note it is
 * about already joined. The getters exist because {@code fragments/ui ::
 * documentHeader} reads bean properties.
 */
public record DamageHeader(
        UUID id,
        UUID branchId,
        String branchName,
        boolean branchBonded,
        String serialNo,
        String status,
        LocalDate documentDate,
        String reference,
        String notes,
        int version,
        UUID createdBy,
        String createdByName,
        LocalDateTime createdAt,
        LocalDateTime submittedAt,
        LocalDateTime postedAt,
        UUID postedBy,
        String postedByName,
        LocalDateTime cancelledAt,
        String cancelledByName,
        String cancelReason,
        DamageKind kind,
        String reasonCode,
        String reason,
        UUID fromLocationId,
        String fromCode,
        String fromName,
        boolean fromBonded,
        UUID toLocationId,
        String toCode,
        String toName,
        boolean toBonded,
        UUID transferId,
        String transferSerial,
        UUID deliveryNoteId,
        String deliveryNoteSerial,
        String customsReference
) {

    public boolean bonded() {
        return branchBonded || fromBonded || toBonded;
    }

    public String getSerialNo()         { return serialNo; }
    public String getStatus()           { return status; }
    public boolean isBonded()           { return bonded(); }
    public String getBranchName()       { return branchName; }
    public String getCreatedByName()    { return createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getReasonLabel()      { return DamageKind.reasonLabel(reasonCode); }
}
