package heritier.ntaganira.highbytes.wms.admin.branch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.branch
 * - File       : BranchType.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What kind of branch it is: the main one, an ordinary one, or a bonded warehouse
 * </pre>
 */

/** {@code branch.branch_type} (V1). There is one main branch (V17); a bonded warehouse is bonded. */
public enum BranchType {

    MAIN("Main warehouse"),
    BRANCH("Branch"),
    BONDED("Bonded warehouse");

    private final String label;

    BranchType(String label) {
        this.label = label;
    }

    public String label() { return label; }
}
