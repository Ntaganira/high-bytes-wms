package heritier.ntaganira.highbytes.wms.masterdata.supplier;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.supplier
 * - File       : SupplierForm.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The supplier create/edit form and its validation rules
 * </pre>
 */

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The supplier form. Whether a supplier is foreign is not asked: it follows
 * from the country, so the two can never disagree.
 */
public class SupplierForm {

    private UUID id;

    @NotBlank(message = "A supplier code is required")
    @Size(max = 24, message = "A supplier code is at most 24 characters")
    @Pattern(regexp = "^$|^[A-Za-z0-9][A-Za-z0-9._-]*$",
             message = "Use letters, digits and . _ - only, starting with a letter or digit")
    private String code;

    @NotBlank(message = "A name is required")
    @Size(max = 200, message = "A name is at most 200 characters")
    private String name;

    @Size(max = 24, message = "A TIN is at most 24 characters")
    private String tin;

    @NotBlank(message = "Enter the two-letter country code")
    @Pattern(regexp = "^$|^[A-Za-z]{2}$", message = "Use a two-letter country code, such as RW or CN")
    private String countryCode = "RW";

    @Size(max = 300, message = "An address is at most 300 characters")
    private String address;

    @Size(max = 40, message = "A phone number is at most 40 characters")
    private String phone;

    @Email(message = "That is not a valid email address")
    @Size(max = 160, message = "An email address is at most 160 characters")
    private String email;

    private boolean active = true;

    public boolean isNew() { return id == null; }

    public boolean isForeign() {
        return countryCode != null && !"RW".equalsIgnoreCase(countryCode);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code == null ? null : code.trim().toUpperCase(); }

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.trim(); }

    public String getTin() { return tin; }
    public void setTin(String tin) { this.tin = blankToNull(tin); }

    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode == null ? null : countryCode.trim().toUpperCase();
    }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = blankToNull(address); }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = blankToNull(phone); }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = blankToNull(email); }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
