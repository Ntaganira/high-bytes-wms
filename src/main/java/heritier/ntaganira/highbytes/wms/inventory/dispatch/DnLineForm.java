package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DnLineForm.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One line of the delivery note form, bound as lines[n].field
 * </pre>
 */

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A loaded line. Item and unit are not on it: they are the authorization
 * line's, and the service reads them from there, so a note cannot load
 * something the authorization does not list. A line may be split across bins;
 * the lines serving one authorization line must add up to its quantity.
 */
public class DnLineForm {

    @NotNull(message = "This line must serve an authorization line")
    private UUID authorizationLineId;

    @NotNull(message = "Enter the quantity loaded")
    @DecimalMin(value = "0.001", message = "The quantity must be more than zero")
    @Digits(integer = 13, fraction = 3, message = "At most 3 decimal places")
    private BigDecimal quantity;

    private UUID storageBinId;

    @DecimalMin(value = "0.01", message = "The thickness must be more than zero")
    @Digits(integer = 4, fraction = 2, message = "At most 2 decimal places")
    private BigDecimal measuredThicknessMm;

    public UUID getAuthorizationLineId() { return authorizationLineId; }
    public void setAuthorizationLineId(UUID id) { this.authorizationLineId = id; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public UUID getStorageBinId() { return storageBinId; }
    public void setStorageBinId(UUID storageBinId) { this.storageBinId = storageBinId; }

    public BigDecimal getMeasuredThicknessMm() { return measuredThicknessMm; }
    public void setMeasuredThicknessMm(BigDecimal value) { this.measuredThicknessMm = value; }
}
