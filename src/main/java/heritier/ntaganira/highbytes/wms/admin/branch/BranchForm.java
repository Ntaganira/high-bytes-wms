package heritier.ntaganira.highbytes.wms.admin.branch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.branch
 * - File       : BranchForm.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A branch as its form holds it, with the shape each field must have
 * </pre>
 */

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A branch as its form holds it. The code is printed in every serial raised there
 * ({@code GRN-KGL-2026-0412}), so it is short, capitals and digits, and never changes once saved. The name is Latin
 * script, as role names are (V10, V17): the audit trail names a branch as text, and a look-alike letter would make
 * a second branch read exactly like the first.
 */
public class BranchForm {

    private UUID id;

    @NotBlank(message = "A code is required")
    // The pattern accepts an empty value so a blank code reports only that it is required.
    @Pattern(regexp = "^$|^[A-Z][A-Z0-9]{1,7}$",
             message = "Two to eight capital letters or digits, starting with a letter: every serial raised there prints it")
    private String code;

    @NotBlank(message = "A name is required")
    @Size(max = 120, message = "A name is at most 120 characters")
    @Pattern(regexp = "^$|^[A-Za-z0-9À-ɏ &()'.,/–—-]+$",
             message = "Use Latin letters, digits, spaces and . , ' ( ) / - only")
    private String name;

    @NotNull(message = "Choose what kind of branch it is")
    private BranchType branchType;

    @Size(max = 80, message = "A city is at most 80 characters")
    private String city;

    @NotBlank(message = "A country is required")
    @Pattern(regexp = "^$|^[A-Z]{2}$", message = "The two-letter country code, such as RW or CD")
    private String countryCode = "RW";

    private boolean bonded;

    @Size(max = 60, message = "A customs regime is at most 60 characters")
    private String customsRegime;

    /** Set when editing: documents raised or stock moved there fix its type and whether it is bonded. */
    private boolean hasHistory;

    public BranchForm() {}

    public boolean isNew() { return id == null; }

    // ---- accessors -------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) {
        this.code = code == null ? null : code.trim().toUpperCase();
    }

    public String getName() { return name; }
    /** Trimmed, and runs of spaces made one: the database refuses a name with either (V17). */
    public void setName(String name) {
        this.name = name == null ? null : name.trim().replaceAll("\\s{2,}", " ");
    }

    public BranchType getBranchType() { return branchType; }
    public void setBranchType(BranchType branchType) { this.branchType = branchType; }

    public String getCity() { return city; }
    public void setCity(String city) {
        this.city = city == null || city.isBlank() ? null : city.trim();
    }

    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode == null ? null : countryCode.trim().toUpperCase();
    }

    public boolean isBonded() { return bonded; }
    public void setBonded(boolean bonded) { this.bonded = bonded; }

    public String getCustomsRegime() { return customsRegime; }
    public void setCustomsRegime(String customsRegime) {
        this.customsRegime = customsRegime == null || customsRegime.isBlank() ? null : customsRegime.trim();
    }

    public boolean isHasHistory() { return hasHistory; }
    public void setHasHistory(boolean hasHistory) { this.hasHistory = hasHistory; }
}
