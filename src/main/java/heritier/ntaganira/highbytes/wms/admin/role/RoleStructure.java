package heritier.ntaganira.highbytes.wms.admin.role;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.role
 * - File       : RoleStructure.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Which organisation structure a role belongs to: the 2026 policy, the 2027 restructuring, or both
 * </pre>
 */

/**
 * Which structure a role belongs to. Matches the CHECK constraint on
 * {@code role.structure}. A role from one structure only stays selectable,
 * because workflow definitions for the other period still name it.
 */
public enum RoleStructure {

    BOTH("Both structures", "Exists under the 2026 policy and the 2027 restructuring."),
    POLICY_2026("2026 policy", "Belongs to the Inventory Policy structure, which the restructuring replaces on 1 January 2027."),
    RESTRUCTURE_2027("2027 structure", "Created by Board Paper HB/BD/2026/09-05, in force from 1 January 2027.");

    private final String label;
    private final String description;

    RoleStructure(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() { return label; }
    public String description() { return description; }
}
