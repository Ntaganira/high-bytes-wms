package heritier.ntaganira.highbytes.wms.admin.role;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.role
 * - File       : PermissionGroup.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The permission catalogue for one module, as the permission screens group it
 * </pre>
 */

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The permissions of one module, in the order the document lifecycle runs. */
public record PermissionGroup(String module, List<Permission> permissions) {

    /** Module names as a warehouse officer says them, in the order the menu shows them. */
    static final Map<String, String> MODULE_LABELS = new LinkedHashMap<>();
    static {
        MODULE_LABELS.put("receiving",  "Goods received");
        MODULE_LABELS.put("dispatch",   "Dispatch");
        MODULE_LABELS.put("transfer",   "Transfers");
        MODULE_LABELS.put("cutting",    "Cutting");
        MODULE_LABELS.put("damage",     "Returns & damage");
        MODULE_LABELS.put("count",      "Stock counts");
        MODULE_LABELS.put("ticket",     "Transaction tickets");
        MODULE_LABELS.put("inventory",  "Inventory");
        MODULE_LABELS.put("masterdata", "Master data");
        MODULE_LABELS.put("reporting",  "Reporting");
        MODULE_LABELS.put("audit",      "Audit");
        MODULE_LABELS.put("admin",      "Administration");
    }

    public String label() {
        return MODULE_LABELS.getOrDefault(module, module);
    }

    /** Where this module sorts; unknown modules go last. */
    static int orderOf(String module) {
        int i = 0;
        for (String known : MODULE_LABELS.keySet()) {
            if (known.equals(module)) return i;
            i++;
        }
        return i;
    }

    /**
     * One catalogued permission. {@code duty} is ADMINISTER, TRANSACT,
     * CONFIGURE or READ: V9 derives it from module and action, and
     * invariant 8 is stated in its terms.
     */
    public record Permission(UUID id, String code, String module, String action,
                             String description, String duty) {}
}
