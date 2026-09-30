package heritier.ntaganira.highbytes.wms.masterdata.customer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.customer
 * - File       : CustomerForm.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The customer create/edit form and its validation rules
 * </pre>
 */

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The customer form. Blocking is not on it: it is its own action with its own
 * reason, so a block is never a side effect of saving an address.
 */
public class CustomerForm {

    private UUID id;

    @NotBlank(message = "A customer code is required")
    @Size(max = 24, message = "A customer code is at most 24 characters")
    @Pattern(regexp = "^$|^[A-Za-z0-9][A-Za-z0-9._-]*$",
             message = "Use letters, digits and . _ - only, starting with a letter or digit")
    private String code;

    @NotBlank(message = "A name is required")
    @Size(max = 200, message = "A name is at most 200 characters")
    private String name;

    @Size(max = 24, message = "A TIN is at most 24 characters")
    private String tin;

    @NotNull(message = "Choose the market")
    private CustomerMarket market = CustomerMarket.DOMESTIC;

    @Size(max = 300, message = "An address is at most 300 characters")
    private String address;

    @Size(max = 40, message = "A phone number is at most 40 characters")
    private String phone;

    @Email(message = "That is not a valid email address")
    @Size(max = 160, message = "An email address is at most 160 characters")
    private String email;

    @DecimalMin(value = "0", message = "A credit limit cannot be negative")
    @Digits(integer = 16, fraction = 2, message = "At most 2 decimal places")
    private BigDecimal creditLimit;

    @Min(value = 0, message = "Payment terms cannot be negative")
    @Max(value = 365, message = "Payment terms are at most 365 days")
    private Integer paymentTermsDays;

    private boolean active = true;

    public boolean isNew() { return id == null; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code == null ? null : code.trim().toUpperCase(); }

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.trim(); }

    public String getTin() { return tin; }
    public void setTin(String tin) { this.tin = blankToNull(tin); }

    public CustomerMarket getMarket() { return market; }
    public void setMarket(CustomerMarket market) { this.market = market; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = blankToNull(address); }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = blankToNull(phone); }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = blankToNull(email); }

    public BigDecimal getCreditLimit() { return creditLimit; }
    public void setCreditLimit(BigDecimal creditLimit) { this.creditLimit = creditLimit; }

    public Integer getPaymentTermsDays() { return paymentTermsDays; }
    public void setPaymentTermsDays(Integer paymentTermsDays) { this.paymentTermsDays = paymentTermsDays; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
