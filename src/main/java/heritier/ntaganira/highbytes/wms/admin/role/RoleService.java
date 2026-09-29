package heritier.ntaganira.highbytes.wms.admin.role;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.role
 * - File       : RoleService.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Roles and the permissions they carry: create, amend, set permissions, deactivate, audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.security.AccessChangeRefusedException;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Roles and the permissions they carry (FR-SEC-15, FR-SEC-16).
 *
 * <p>Permissions are given to roles, never to users. A change here reaches
 * every holder on their next request. The rules:
 * <ul>
 *   <li>A role the policy defines — protected, named by a segregation rule,
 *       or signing a step in a chain in force or scheduled — keeps the
 *       permissions the policy gave it. Those change by migration only: the
 *       rules that name the role assume what it carries (V9).</li>
 *   <li>No role carries an administration permission alongside anything
 *       operational, and no change may leave a current holder with both
 *       across their roles (invariant 8). A role no segregation rule names
 *       may not carry the rights of both sides of one (V9 refuses both).</li>
 *   <li>Nobody changes a role they hold themselves, not even its name: the
 *       audit trail records their actions under it.</li>
 *   <li>A role that signs a step in an approval chain in force or scheduled
 *       cannot be deactivated, or that step would have nobody able to sign.
 *       Protected roles are never deactivated.</li>
 * </ul>
 * Refused attempts are recorded; the changes themselves commit together
 * with their audit rows.
 */
@Service
@Transactional(readOnly = true)
public class RoleService {

    private static final String SELECT = """
            SELECT r.id, r.code, r.name, r.description, r.structure, r.is_protected, r.is_active,
                   r.created_at,
                   (SELECT count(*) FROM role_permission rp WHERE rp.role_id = r.id) AS permission_count,
                   (SELECT count(DISTINCT ur.user_id)
                      FROM live_user_role ur
                      JOIN app_user u ON u.id = ur.user_id AND u.is_active
                     WHERE ur.role_id = r.id) AS holder_count,
                   (SELECT count(*)
                      FROM workflow_step ws
                      JOIN workflow_definition wd ON wd.id = ws.workflow_definition_id
                     WHERE ws.required_role_id = r.id
                       AND (wd.effective_to IS NULL OR wd.effective_to > CURRENT_DATE)) AS step_count,
                   EXISTS (SELECT 1 FROM role_permission rp
                             JOIN permission p ON p.id = rp.permission_id
                            WHERE rp.role_id = r.id AND p.duty = 'ADMINISTER') AS administers,
                   role_is_policy_defined(r.id) AS policy_defined
              FROM role r
            """;

    /** The lifecycle order: view, raise, amend, submit, verify, approve, release, post, cancel, configure. */
    private static final String CATALOGUE = """
            SELECT id, code, module, action, description, duty
              FROM permission
             ORDER BY module,
                      CASE action WHEN 'VIEW' THEN 0 WHEN 'CREATE' THEN 1 WHEN 'AMEND' THEN 2
                                  WHEN 'SUBMIT' THEN 3 WHEN 'VERIFY' THEN 4 WHEN 'APPROVE' THEN 5
                                  WHEN 'RELEASE' THEN 6 WHEN 'POST' THEN 7 WHEN 'CANCEL' THEN 8
                                  ELSE 9 END,
                      code
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public RoleService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- reads -----------------------------------------------------------

    public List<RoleRow> list() {
        return jdbc.sql(SELECT + " ORDER BY r.is_active DESC, r.name")
                .query(this::map)
                .list();
    }

    public Optional<RoleRow> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id")
                .param("id", id, Types.OTHER)
                .query(this::map)
                .optional();
    }

    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM role WHERE id = :id)")
                .param("id", id, Types.OTHER)
                .query(Boolean.class)
                .single();
    }

    /** The role as its form holds it; a 404 when no role has that id. */
    public RoleForm formFor(UUID id) {
        return jdbc.sql("SELECT id, code, name, description, structure FROM role WHERE id = :id")
                .param("id", id, Types.OTHER)
                .query((rs, n) -> {
                    var f = new RoleForm();
                    f.setId(rs.getObject("id", UUID.class));
                    f.setCode(rs.getString("code"));
                    f.setName(rs.getString("name"));
                    f.setDescription(rs.getString("description"));
                    f.setStructure(RoleStructure.valueOf(rs.getString("structure")));
                    return f;
                })
                .optional()
                .orElseThrow(() -> new RoleNotFoundException(id));
    }

    /** Every catalogued permission, grouped by module in menu order. */
    public List<PermissionGroup> catalogue() {
        var permissions = jdbc.sql(CATALOGUE)
                .query((rs, n) -> new PermissionGroup.Permission(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("module"),
                        rs.getString("action"),
                        rs.getString("description"),
                        rs.getString("duty")))
                .list();

        Map<String, List<PermissionGroup.Permission>> byModule = new LinkedHashMap<>();
        permissions.stream()
                .sorted(Comparator.comparingInt(p -> PermissionGroup.orderOf(p.module())))
                .forEach(p -> byModule.computeIfAbsent(p.module(), m -> new ArrayList<>()).add(p));

        List<PermissionGroup> groups = new ArrayList<>();
        byModule.forEach((module, list) -> groups.add(new PermissionGroup(module, list)));
        return groups;
    }

    public Set<String> permissionCodes(UUID roleId) {
        return new HashSet<>(jdbc.sql("""
                SELECT p.code FROM role_permission rp
                  JOIN permission p ON p.id = rp.permission_id
                 WHERE rp.role_id = :id
                """)
                .param("id", roleId, Types.OTHER)
                .query(String.class)
                .list());
    }

    /** Who holds the role now or will: unrevoked assignments that have not ended. */
    public List<Holder> holders(UUID roleId) {
        return jdbc.sql("""
                SELECT u.id, u.full_name, u.username, u.is_active, b.name AS branch_name,
                       ur.valid_from, ur.valid_to, ur.is_delegation
                  FROM user_role ur
                  JOIN app_user u  ON u.id = ur.user_id
             LEFT JOIN branch b    ON b.id = ur.branch_id
                 WHERE ur.role_id = :id
                   AND ur.revoked_at IS NULL
                   AND (ur.valid_to IS NULL OR ur.valid_to >= CURRENT_DATE)
                 ORDER BY u.is_active DESC, lower(u.full_name), ur.valid_from
                """)
                .param("id", roleId, Types.OTHER)
                .query((rs, n) -> new Holder(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("username"),
                        rs.getBoolean("is_active"),
                        rs.getString("branch_name"),
                        rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class),
                        rs.getBoolean("is_delegation")))
                .list();
    }

    /** The steps the role signs in approval chains in force or scheduled. */
    public List<ChainStep> chainSteps(UUID roleId) {
        return jdbc.sql("""
                SELECT dt.name AS document_type, dt.form_reference, wd.version, wd.basis,
                       wd.effective_from, wd.effective_to, ws.sequence_no, ws.action_label
                  FROM workflow_step ws
                  JOIN workflow_definition wd ON wd.id = ws.workflow_definition_id
                  JOIN document_type dt       ON dt.id = wd.document_type_id
                 WHERE ws.required_role_id = :id
                   AND (wd.effective_to IS NULL OR wd.effective_to > CURRENT_DATE)
                 ORDER BY dt.sort_order, wd.effective_from, ws.sequence_no
                """)
                .param("id", roleId, Types.OTHER)
                .query((rs, n) -> new ChainStep(
                        rs.getString("document_type"),
                        rs.getString("form_reference"),
                        rs.getInt("version"),
                        rs.getString("basis"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        rs.getInt("sequence_no"),
                        rs.getString("action_label")))
                .list();
    }

    /** The segregation rules that name this role, from its side. */
    public List<Segregation> segregationOf(UUID roleId) {
        return jdbc.sql("""
                SELECT o.id AS other_id, o.name AS other_name, s.enforcement, s.rationale, s.source_ref
                  FROM sod_rule s
                  JOIN role o ON o.id = CASE WHEN s.role_a_id = :id THEN s.role_b_id ELSE s.role_a_id END
                 WHERE s.is_active AND (s.role_a_id = :id OR s.role_b_id = :id)
                 ORDER BY o.name
                """)
                .param("id", roleId, Types.OTHER)
                .query((rs, n) -> new Segregation(
                        null,
                        rs.getObject("other_id", UUID.class),
                        rs.getString("other_name"),
                        rs.getString("enforcement"),
                        rs.getString("rationale"),
                        rs.getString("source_ref")))
                .list();
    }

    /** Every segregation rule in force, for the roles list. */
    public List<Segregation> segregationRules() {
        return jdbc.sql("""
                SELECT ra.name AS role_a, rb.id AS other_id, rb.name AS role_b,
                       s.enforcement, s.rationale, s.source_ref
                  FROM sod_rule s
                  JOIN role ra ON ra.id = s.role_a_id
                  JOIN role rb ON rb.id = s.role_b_id
                 WHERE s.is_active
                 ORDER BY ra.name, rb.name
                """)
                .query((rs, n) -> new Segregation(
                        rs.getString("role_a"),
                        rs.getObject("other_id", UUID.class),
                        rs.getString("role_b"),
                        rs.getString("enforcement"),
                        rs.getString("rationale"),
                        rs.getString("source_ref")))
                .list();
    }

    /** Whether the signed-in user holds the role now or is due to. */
    public boolean heldByCurrentUser(UUID roleId) {
        UUID me = CurrentUser.id();
        if (me == null) return false;
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM user_role
                                WHERE user_id = :me AND role_id = :role AND revoked_at IS NULL
                                  AND (valid_to IS NULL OR valid_to >= CURRENT_DATE))
                """)
                .param("me", me, Types.OTHER)
                .param("role", roleId, Types.OTHER)
                .query(Boolean.class)
                .single();
    }

    // ---- writes ----------------------------------------------------------

    @Transactional
    @PreAuthorize("hasAuthority('admin.roles')")
    public UUID create(RoleForm form, BranchView branch) {
        refuseTakenName(null, form.getName());
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO role (id, code, name, description, structure, is_protected, is_active)
                    VALUES (:id, :code, :name, :description, :structure, FALSE, TRUE)
                    """)
                    .param("id", id, Types.OTHER)
                    .param("code", form.getCode())
                    .param("name", form.getName())
                    .param("description", form.getDescription())
                    .param("structure", form.getStructure().name())
                    .update();
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMessage()).contains("role_name_ci")) {
                throw new RoleNameTakenException(form.getName());
            }
            throw new RoleCodeTakenException(form.getCode());
        }

        audit.recordAccessChange("role", id, label(form.getName()), AuditAction.CREATE,
                snapshotOf(null, form), branch, null);
        return id;
    }

    /**
     * Name, description and structure. The code never changes, and a holder
     * changes none of it: the name is the title their actions are audited under.
     */
    @Transactional
    @PreAuthorize("hasAuthority('admin.roles')")
    public void update(UUID id, RoleForm form, BranchView branch) {
        RoleForm before = formFor(id);
        var role = findById(id).orElseThrow(() -> new RoleNotFoundException(id));
        refuseIfHeld(role, "Change details", "rename or re-describe it", branch);
        if (role.policyDefined()) {
            throw refused(role, "Change details", role.name() + " is defined by the policy, so its name and"
                    + " description change only by a reviewed migration.", branch);
        }
        refuseTakenName(id, form.getName());

        jdbc.sql("""
                UPDATE role SET name = :name, description = :description,
                                structure = :structure, updated_at = now()
                 WHERE id = :id
                """)
                .param("id", id, Types.OTHER)
                .param("name", form.getName())
                .param("description", form.getDescription())
                .param("structure", form.getStructure().name())
                .update();

        var snapshot = snapshotOf(before, form);
        if (!snapshot.unchanged()) {
            audit.recordAccessChange("role", id, label(form.getName()), AuditAction.UPDATE, snapshot, branch, null);
        }
    }

    /**
     * Replaces the role's permissions with {@code codes}. Refused, with the
     * reason, when the policy defines the role, when the role would carry
     * what no one person may hold, or when any current holder would across
     * their roles. Returns false when nothing changed.
     */
    @Transactional
    @PreAuthorize("hasAuthority('admin.roles')")
    public boolean setPermissions(UUID id, Collection<String> codes, BranchView branch) {
        var role = findById(id).orElseThrow(() -> new RoleNotFoundException(id));
        refuseIfHeld(role, "Change permissions", "change what it permits", branch);

        // One access change at a time (V9), taken before reading what is
        // replaced, so the record's "before" is what this change replaced.
        jdbc.sql("SELECT lock_access_changes()").query(Boolean.class).single();

        var catalogue = catalogue().stream().flatMap(g -> g.permissions().stream()).toList();
        Set<String> known = new HashSet<>();
        catalogue.forEach(p -> known.add(p.code()));
        Set<String> after = new HashSet<>(codes == null ? List.of() : codes);
        after.removeIf(code -> !known.contains(code));

        Set<String> before = permissionCodes(id);
        Set<String> removed = new HashSet<>(before);
        removed.removeAll(after);
        Set<String> added = new HashSet<>(after);
        added.removeAll(before);
        if (removed.isEmpty() && added.isEmpty()) return false;

        String attempt = "Permissions · " + describe(added, removed);
        if (role.policyDefined()) {
            throw refused(role, attempt, role.name() + " is defined by the policy, so what it permits changes only"
                    + " by a reviewed migration: the segregation rules and approval chains that name it assume"
                    + " what it carries.", branch);
        }

        if (!removed.isEmpty()) {
            jdbc.sql("""
                    DELETE FROM role_permission
                     WHERE role_id = :id
                       AND permission_id IN (SELECT id FROM permission WHERE code IN (:codes))
                    """)
                    .param("id", id, Types.OTHER)
                    .param("codes", removed)
                    .update();
        }
        if (!added.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO role_permission (role_id, permission_id)
                    SELECT :id, p.id FROM permission p WHERE p.code IN (:codes)
                    """)
                    .param("id", id, Types.OTHER)
                    .param("codes", added)
                    .update();
        }

        // The same check the database runs at commit, asked first so the
        // refusal carries its reason. The change is undone; the attempt stays
        // on record.
        String conflict = jdbc.sql("SELECT role_access_conflict(:id)")
                .param("id", id, Types.OTHER)
                .query(String.class)
                .optional()
                .orElse(null);
        if (conflict != null) {
            throw refused(role, attempt, conflict, branch);
        }

        // Every permission in the snapshot, so the stored record is whole;
        // the history shows only the ones that moved.
        var snapshot = AuditSnapshot.of();
        for (var p : catalogue) {
            snapshot.field(p.code() + " · " + p.description(), before.contains(p.code()), after.contains(p.code()));
        }
        audit.recordAccessChange("role", id, label(role.name()), AuditAction.UPDATE, snapshot, branch, null);
        return true;
    }

    @Transactional
    @PreAuthorize("hasAuthority('admin.roles')")
    public void deactivate(UUID id, String reason, BranchView branch) {
        var role = findById(id).orElseThrow(() -> new RoleNotFoundException(id));
        if (!role.active()) return;
        refuseIfHeld(role, "Deactivate", "deactivate it", branch);

        if (role.protectedRole()) {
            throw refused(role, "Deactivate", role.name() + " is protected: the policy defines it, "
                    + "so it is never deactivated.", branch);
        }
        if (role.stepCount() > 0) {
            throw refused(role, "Deactivate", role.name() + " signs " + role.stepCount()
                    + (role.stepCount() == 1 ? " step" : " steps")
                    + " in approval chains in force or scheduled. Deactivated, nobody could sign "
                    + (role.stepCount() == 1 ? "it" : "them") + ". Change the chains first.", branch);
        }
        if (role.policyDefined()) {
            throw refused(role, "Deactivate", role.name() + " is defined by the policy: a segregation rule or"
                    + " an approval chain names it, so it is never deactivated.", branch);
        }
        if (reason == null || reason.isBlank()) {
            throw new AccessChangeRefusedException("Give a reason for deactivating the role. It is kept in the audit trail.");
        }
        String why = reason.trim();
        if (why.length() > REASON_MAX) {
            throw new AccessChangeRefusedException("Keep the reason to " + REASON_MAX
                    + " characters: it is kept whole, with the change it explains.");
        }

        jdbc.sql("UPDATE role SET is_active = FALSE, updated_at = now() WHERE id = :id")
                .param("id", id, Types.OTHER)
                .update();

        audit.recordAccessChange("role", id, label(role.name()), AuditAction.DEACTIVATE,
                AuditSnapshot.of().field("Active", true, false), branch, why);
    }

    @Transactional
    @PreAuthorize("hasAuthority('admin.roles')")
    public void reactivate(UUID id, BranchView branch) {
        var role = findById(id).orElseThrow(() -> new RoleNotFoundException(id));
        if (role.active()) return;
        refuseIfHeld(role, "Reactivate", "reactivate it", branch);

        jdbc.sql("UPDATE role SET is_active = TRUE, updated_at = now() WHERE id = :id")
                .param("id", id, Types.OTHER)
                .update();

        audit.recordAccessChange("role", id, label(role.name()), AuditAction.UPDATE,
                AuditSnapshot.of().field("Active", false, true), branch, null);
    }

    // ---- helpers ---------------------------------------------------------

    /** Nobody changes a role they hold. The attempt is recorded. */
    private void refuseIfHeld(RoleRow role, String attempt, String what, BranchView branch) {
        if (heldByCurrentUser(role.id())) {
            throw refused(role, attempt, "You hold " + role.name() + ", so you cannot " + what
                    + ". Another administrator must: nobody administers their own access.", branch);
        }
    }

    /**
     * Records a refused change to a role and returns the exception that
     * tells the administrator why. The record is written once the refusal
     * has rolled back, in a transaction of its own.
     */
    private AccessChangeRefusedException refused(RoleRow role, String attempt, String why, BranchView branch) {
        audit.recordRefusal("role", role.id(), label(role.name()),
                AuditSnapshot.of().value("Attempted", attempt), branch, "Refused: " + why);
        return new AccessChangeRefusedException(why);
    }

    /** Role names are unique, ignoring case (V9): holders and the audit trail read them. */
    private void refuseTakenName(UUID self, String name) {
        boolean taken = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM role
                                WHERE lower(name) = lower(:name) AND id IS DISTINCT FROM :self::uuid)
                """)
                .param("name", name)
                .param("self", self, Types.OTHER)
                .query(Boolean.class)
                .single();
        if (taken) throw new RoleNameTakenException(name);
    }

    private static String describe(Set<String> added, Set<String> removed) {
        var parts = new ArrayList<String>();
        added.stream().sorted().forEach(code -> parts.add("+" + code));
        removed.stream().sorted().forEach(code -> parts.add("−" + code));
        return String.join(", ", parts);
    }

    private AuditSnapshot snapshotOf(RoleForm before, RoleForm after) {
        var snap = AuditSnapshot.of();
        if (before == null) snap.value("Code", after.getCode());
        return snap
                .field("Name",        before == null ? null : before.getName(),        after.getName())
                .field("Description", before == null ? null : before.getDescription(), after.getDescription())
                .field("Structure",   before == null ? null : before.getStructure().label(),
                                      after.getStructure().label());
    }

    private static String label(String name) {
        return "Role · " + name;
    }

    /** A reason is kept whole, with the change it explains. */
    static final int REASON_MAX = 240;

    private RoleRow map(ResultSet rs, int rowNum) throws SQLException {
        var created = rs.getTimestamp("created_at");
        return new RoleRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                RoleStructure.valueOf(rs.getString("structure")),
                rs.getBoolean("is_protected"),
                rs.getBoolean("is_active"),
                created == null ? null : created.toLocalDateTime(),
                rs.getInt("permission_count"),
                rs.getInt("holder_count"),
                rs.getInt("step_count"),
                rs.getBoolean("administers"),
                rs.getBoolean("policy_defined"));
    }

    // ---- small views -------------------------------------------------------

    /** Someone who holds the role now or from a later date. */
    public record Holder(UUID userId, String fullName, String username, boolean userActive,
                         String branchName, LocalDate validFrom, LocalDate validTo, boolean delegation) {
        public String scope() {
            return branchName == null ? "All branches" : branchName;
        }

        public String state() {
            if (!userActive) return "INACTIVE";
            return validFrom.isAfter(LocalDate.now()) ? "SCHEDULED" : "ACTIVE";
        }
    }

    /** One step the role signs, and the chain it belongs to. */
    public record ChainStep(String documentType, String formReference, int version, String basis,
                            LocalDate effectiveFrom, LocalDate effectiveTo, int sequenceNo, String actionLabel) {}

    /**
     * A segregation rule. From a role's page {@code roleA} is null and
     * {@code other} is the role it may not be combined with.
     */
    public record Segregation(String roleA, UUID otherId, String other, String enforcement,
                              String rationale, String sourceRef) {}

    // ---- failures the user should see, not a stack trace -------------------

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class RoleNotFoundException extends RuntimeException {
        public RoleNotFoundException(UUID id) {
            super("No role with id " + id);
        }
    }

    public static class RoleCodeTakenException extends RuntimeException {
        public RoleCodeTakenException(String code) {
            super("Role code " + code + " is already in use.");
        }
    }

    public static class RoleNameTakenException extends RuntimeException {
        public RoleNameTakenException(String name) {
            super("A role named " + name + " already exists. Holders and the audit trail read the name,"
                    + " so two roles cannot share one.");
        }
    }
}
