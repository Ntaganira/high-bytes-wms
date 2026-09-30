package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : GrnHeader.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of one goods received note's header, for the view and the form
 * </pre>
 */

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A goods received note's header as the screens read it: the spine columns and
 * the receipt's own, with the names already joined.
 *
 * <p>The getters at the bottom exist because {@code fragments/ui ::
 * documentHeader} reads bean properties.
 */
public record GrnHeader(
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
        LocalDateTime approvedAt,
        LocalDateTime postedAt,
        UUID postedBy,
        String postedByName,
        LocalDateTime cancelledAt,
        String cancelledByName,
        String cancelReason,
        UUID supersedesId,
        String supersedesSerial,
        UUID supplierId,
        String supplierName,
        UUID locationId,
        String locationCode,
        String locationName,
        boolean locationBonded,
        String deliveryNoteNo,
        String invoiceNo,
        String purchaseOrderNo,
        String customsReference,
        String currencyCode,
        BigDecimal exchangeRate,
        BigDecimal freightRwf,
        BigDecimal dutyRwf,
        BigDecimal clearingRwf,
        BigDecimal demurrageRwf
) {

    /** Bonded when the branch or the receiving location is: the customs reference is then required. */
    public boolean bonded() {
        return branchBonded || locationBonded;
    }

    /** Freight, duty, clearing and demurrage together, in RWF. */
    public BigDecimal landedTotalRwf() {
        return freightRwf.add(dutyRwf).add(clearingRwf).add(demurrageRwf);
    }

    public boolean foreignCurrency() {
        return !"RWF".equals(currencyCode);
    }

    public String getSerialNo()        { return serialNo; }
    public String getStatus()          { return status; }
    public boolean isBonded()          { return bonded(); }
    public String getBranchName()      { return branchName; }
    public String getCreatedByName()   { return createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
