package heritier.ntaganira.highbytes.wms.admin.role;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.role
 * - File       : RoleForm.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The role create/edit form and its validation rules
 * </pre>
 */

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;
import java.util.UUID;

/**
 * The create/edit form. Permissions are not on it: they are edited on
 * their own screen, where the duty of each is visible.
 *
 * <p>The code cannot change once created. Workflow definitions and
 * segregation rules name roles, and a code that moved would make those
 * rows read differently from what was decided.
 */
public class RoleForm {

    private UUID id;

    @NotBlank(message = "A code is required")
    @Size(max = 48, message = "A code is at most 48 characters")
    @Pattern(regexp = "^$|^[A-Z][A-Z0-9_]*$",
             message = "Use capital letters, digits and _ only, starting with a letter")
    private String code;

    // Latin script only (V10 role_name_readable): a Cyrillic letter or an
    // invisible character would make a role read exactly like another.
    @NotBlank(message = "A name is required")
    @Size(max = 120, message = "A name is at most 120 characters")
    @Pattern(regexp = "^$|^[A-Za-z0-9\\u00C0-\\u024F &()'.,/\\u2013\\u2014-]*$",
             message = "Use letters, digits, spaces and & ( ) ' . , / - only")
    private String name;

    @Size(max = 400, message = "A description is at most 400 characters")
    private String description;

    @NotNull(message = "Choose a structure")
    private RoleStructure structure = RoleStructure.BOTH;

    public RoleForm() {}

    public boolean isNew() { return id == null; }

    // ---- accessors -------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) {
        this.code = code == null ? null : code.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    public String getName() { return name; }
    public void setName(String name) {
        this.name = name == null ? null
                : name.replaceAll("\\p{Cf}", "").replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    public String getDescription() { return description; }
    public void setDescription(String description) {
        this.description = (description == null || description.isBlank()) ? null : description.trim();
    }

    public RoleStructure getStructure() { return structure; }
    public void setStructure(RoleStructure structure) { this.structure = structure; }
}
