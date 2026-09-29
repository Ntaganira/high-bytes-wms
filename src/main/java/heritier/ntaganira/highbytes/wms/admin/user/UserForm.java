package heritier.ntaganira.highbytes.wms.admin.user;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.user
 * - File       : UserForm.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The user create/edit form and its validation rules
 * </pre>
 */

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * The create/edit form.
 *
 * <p>The username cannot change once created. Every signature, posting and
 * audit row carries it, and a person's history must read as one person's.
 * Roles are not on this form: each grant is its own dated, audited act on
 * the user page.
 */
public class UserForm {

    private UUID id;

    // The pattern accepts an empty value so a blank username reports only
    // that it is required.
    @NotBlank(message = "A username is required")
    @Size(max = 60, message = "A username is at most 60 characters")
    @Pattern(regexp = "^$|^[a-z0-9][a-z0-9._-]*$",
             message = "Use lower-case letters, digits and . _ - only, starting with a letter or digit")
    private String username;

    @NotBlank(message = "A full name is required")
    @Size(max = 160, message = "A full name is at most 160 characters")
    private String fullName;

    @Email(message = "Enter a valid email address")
    @Size(max = 160, message = "An email address is at most 160 characters")
    private String email;

    @Size(max = 40, message = "A phone number is at most 40 characters")
    private String phone;

    @NotNull(message = "Choose a home branch")
    private UUID homeBranchId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate lastLeaveStart;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate lastLeaveEnd;

    public UserForm() {}

    public boolean isNew() { return id == null; }

    /** A leave period has both ends, in order, and has at least begun. */
    public boolean isLeaveConsistent() {
        if (lastLeaveStart == null && lastLeaveEnd == null) return true;
        if (lastLeaveStart == null || lastLeaveEnd == null) return false;
        return !lastLeaveEnd.isBefore(lastLeaveStart) && !lastLeaveStart.isAfter(LocalDate.now());
    }

    // ---- accessors -------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) {
        this.username = username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) {
        this.fullName = fullName == null ? null : fullName.trim().replaceAll("\\s+", " ");
    }

    public String getEmail() { return email; }
    public void setEmail(String email) {
        this.email = (email == null || email.isBlank()) ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public String getPhone() { return phone; }
    public void setPhone(String phone) {
        this.phone = (phone == null || phone.isBlank()) ? null : phone.trim();
    }

    public UUID getHomeBranchId() { return homeBranchId; }
    public void setHomeBranchId(UUID homeBranchId) { this.homeBranchId = homeBranchId; }

    public LocalDate getLastLeaveStart() { return lastLeaveStart; }
    public void setLastLeaveStart(LocalDate lastLeaveStart) { this.lastLeaveStart = lastLeaveStart; }

    public LocalDate getLastLeaveEnd() { return lastLeaveEnd; }
    public void setLastLeaveEnd(LocalDate lastLeaveEnd) { this.lastLeaveEnd = lastLeaveEnd; }
}
