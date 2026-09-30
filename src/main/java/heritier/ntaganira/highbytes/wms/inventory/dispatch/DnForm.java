package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DnForm.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The delivery note create/edit form and its validation rules
 * </pre>
 */

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The form. There is no document date (the database dates the note today,
 * Kigali) and no gate time (the posting stamp is the gate time).
 */
public class DnForm {

    private UUID id;
    private Integer version;

    @NotNull(message = "A delivery note is raised against an authorization")
    private UUID authorizationId;

    @NotBlank(message = "Enter the vehicle registration")
    @Size(max = 20, message = "At most 20 characters")
    private String vehicleRegistration;

    @NotBlank(message = "Enter the driver's name")
    @Size(max = 160, message = "At most 160 characters")
    private String driverName;

    @Size(max = 40, message = "At most 40 characters")
    private String driverPhone;

    @Size(max = 40, message = "At most 40 characters")
    private String driverIdNo;

    @Valid
    @Size(min = 1, message = "A delivery note needs at least one line")
    private List<DnLineForm> lines = new ArrayList<>();

    public boolean isNew() { return id == null; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public UUID getAuthorizationId() { return authorizationId; }
    public void setAuthorizationId(UUID authorizationId) { this.authorizationId = authorizationId; }

    public String getVehicleRegistration() { return vehicleRegistration; }
    public void setVehicleRegistration(String v) { this.vehicleRegistration = v == null ? null : v.trim().toUpperCase(); }

    public String getDriverName() { return driverName; }
    public void setDriverName(String v) { this.driverName = v == null ? null : v.trim(); }

    public String getDriverPhone() { return driverPhone; }
    public void setDriverPhone(String v) { this.driverPhone = blankToNull(v); }

    public String getDriverIdNo() { return driverIdNo; }
    public void setDriverIdNo(String v) { this.driverIdNo = blankToNull(v); }

    public List<DnLineForm> getLines() { return lines; }
    public void setLines(List<DnLineForm> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
