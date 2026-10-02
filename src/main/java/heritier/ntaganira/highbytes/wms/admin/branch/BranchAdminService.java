package heritier.ntaganira.highbytes.wms.admin.branch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.branch
 * - File       : BranchAdminService.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Branches as configuration: create, amend, deactivate and reactivate, audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import org.springframework.dao.DataAccessException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Branches as configuration (FR-ADM): a branch is added, amended, deactivated or reactivated here, never by a code
 * change, and never deleted.
 *
 * <p>What the database holds to (V17), whatever path writes: the code never changes, since every serial raised at
 * the branch prints it; its type and whether it is bonded are fixed once a document has been raised or stock has
 * moved there; there is one main branch; and a branch is deactivated only once it is finished with (no stock, no
 * open document, no transfer still to arrive, every day on which stock moved locked). Nothing new starts at an
 * inactive branch. This class asks first, so the refusal can name the field, and the database refuses anyway.
 *
 * <p>Each change and its record commit together; a refusal is recorded after the rollback.
 */
@Service
@Transactional(readOnly = true)
public class BranchAdminService {

    static final String ENTITY = "branch";
    static final String RIGHT = "admin.branches";

    private static final String SELECT = """
            SELECT b.id, b.code, b.name, b.branch_type, b.city, b.country_code, b.is_bonded, b.customs_regime,
                   b.is_active, b.created_at,
                   (SELECT COUNT(*) FROM location l WHERE l.branch_id = b.id AND l.is_active) AS locations,
                   (SELECT COUNT(DISTINCT ur.user_id)
                      FROM user_role ur JOIN app_user u ON u.id = ur.user_id AND u.is_active
                     WHERE ur.branch_id = b.id AND ur.revoked_at IS NULL
                       AND (ur.valid_to IS NULL OR ur.valid_to >= CURRENT_DATE)) AS people,
                   branch_has_history(b.id) AS has_history
              FROM branch b
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public BranchAdminService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** A location at the branch, as the branch page lists it. */
    public record Place(UUID id, String code, String name, String locationType, boolean active) {}

    /** Someone given a role at the branch, in force or due to start. */
    public record Person(UUID userId, String fullName, String username, String roleName,
                         LocalDate validTo, boolean delegation) {}

    // ---- reads -----------------------------------------------------------

    public List<BranchRow> list() {
        return jdbc.sql(SELECT + """
                 ORDER BY b.is_active DESC,
                          CASE b.branch_type WHEN 'MAIN' THEN 0 WHEN 'BRANCH' THEN 1 ELSE 2 END, b.name
                """)
                .query(this::map)
                .list();
    }

    public Optional<BranchRow> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE b.id = :id")
                .param("id", id, Types.OTHER)
                .query(this::map)
                .optional();
    }

    public BranchRow require(UUID id) {
        return find(id).orElseThrow(() -> new BranchNotFoundException(id));
    }

    /** The branch as its form holds it; a 404 when there is none. */
    public BranchForm formFor(UUID id) {
        BranchRow b = require(id);
        var f = new BranchForm();
        f.setId(b.id());
        f.setCode(b.code());
        f.setName(b.name());
        f.setBranchType(b.branchType());
        f.setCity(b.city());
        f.setCountryCode(b.countryCode());
        f.setBonded(b.bonded());
        f.setCustomsRegime(b.customsRegime());
        f.setHasHistory(b.hasHistory());
        return f;
    }

    public List<Place> locations(UUID branchId) {
        return jdbc.sql("""
                SELECT id, code, name, location_type, is_active
                  FROM location WHERE branch_id = :id
                 ORDER BY is_active DESC, location_type, code
                """)
                .param("id", branchId, Types.OTHER)
                .query((rs, n) -> new Place(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("location_type"),
                        rs.getBoolean("is_active")))
                .list();
    }

    public List<Person> people(UUID branchId) {
        return jdbc.sql("""
                SELECT u.id, u.full_name, u.username, r.name AS role_name, ur.valid_to, ur.is_delegation
                  FROM user_role ur
                  JOIN app_user u ON u.id = ur.user_id AND u.is_active
                  JOIN role r     ON r.id = ur.role_id
                 WHERE ur.branch_id = :id AND ur.revoked_at IS NULL
                   AND (ur.valid_to IS NULL OR ur.valid_to >= CURRENT_DATE)
                 ORDER BY lower(u.full_name), r.name
                """)
                .param("id", branchId, Types.OTHER)
                .query((rs, n) -> new Person(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("username"),
                        rs.getString("role_name"),
                        rs.getObject("valid_to", LocalDate.class),
                        rs.getBoolean("is_delegation")))
                .list();
    }

    /**
     * Why the branch cannot be deactivated yet, in the database's words, or null when it can. Names no quantity:
     * the book of a count is on no page (V15).
     */
    public String deactivationBlocker(UUID branchId) {
        return jdbc.sql("SELECT branch_deactivation_blocker(:id)")
                .param("id", branchId, Types.OTHER)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    // ---- writes ----------------------------------------------------------

    @Transactional
    @PreAuthorize("hasAuthority('admin.branches')")
    public UUID create(BranchForm form, BranchView current) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO branch (id, code, name, branch_type, city, country_code, is_bonded, customs_regime)
                    VALUES (:id, :code, :name, :type, :city, :country, :bonded, :regime)
                    """)
                    .param("id", id, Types.OTHER)
                    .param("code", form.getCode())
                    .param("name", form.getName())
                    .param("type", form.getBranchType().name())
                    .param("city", form.getCity())
                    .param("country", form.getCountryCode())
                    .param("bonded", form.isBonded())
                    .param("regime", form.isBonded() ? form.getCustomsRegime() : null)
                    .update();
        } catch (DuplicateKeyException e) {
            throw taken(e, form);
        } catch (DataAccessException e) {
            throw refusedBy(null, "Branch · " + form.getCode(), "Create", e, current);
        }
        audit.recordInTransaction(ENTITY, id, "Branch · " + form.getCode(), AuditAction.CREATE,
                snapshot(null, form), current, null);
        return id;
    }

    @Transactional
    @PreAuthorize("hasAuthority('admin.branches')")
    public void update(UUID id, BranchForm form, BranchView current) {
        BranchForm before = formFor(id);
        // The code is never taken from the form: every serial raised there prints the stored one.
        form.setCode(before.getCode());
        if (before.isHasHistory()
                && (form.getBranchType() != before.getBranchType() || form.isBonded() != before.isBonded())) {
            throw refused(id, "Branch · " + before.getCode(), "Change type", current,
                    "Documents have been raised, or stock has moved, at " + before.getName()
                    + ", so its type and whether it is bonded are fixed: each document was judged against them"
                    + " as they were.");
        }
        try {
            jdbc.sql("""
                    UPDATE branch
                       SET name = :name, branch_type = :type, city = :city, country_code = :country,
                           is_bonded = :bonded, customs_regime = :regime
                     WHERE id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .param("name", form.getName())
                    .param("type", form.getBranchType().name())
                    .param("city", form.getCity())
                    .param("country", form.getCountryCode())
                    .param("bonded", form.isBonded())
                    .param("regime", form.isBonded() ? form.getCustomsRegime() : null)
                    .update();
        } catch (DuplicateKeyException e) {
            throw taken(e, form);
        } catch (DataAccessException e) {
            throw refusedBy(id, "Branch · " + before.getCode(), "Update", e, current);
        }
        var snapshot = snapshot(before, form);
        if (!snapshot.unchanged()) {
            audit.recordInTransaction(ENTITY, id, "Branch · " + before.getCode(), AuditAction.UPDATE,
                    snapshot, current, null);
        }
    }

    @Transactional
    @PreAuthorize("hasAuthority('admin.branches')")
    public void deactivate(UUID id, String reason, BranchView current) {
        BranchRow before = require(id);
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw refused(id, before.label(), "Deactivate", current,
                    "Say why " + before.name() + " is closing: the reason is kept on its record.");
        }
        if (!before.active()) return;
        String blocked = deactivationBlocker(id);
        if (blocked != null) throw refused(id, before.label(), "Deactivate", current, blocked);
        try {
            jdbc.sql("UPDATE branch SET is_active = FALSE WHERE id = :id")
                    .param("id", id, Types.OTHER)
                    .update();
        } catch (DataAccessException e) {
            throw refusedBy(id, before.label(), "Deactivate", e, current);
        }
        audit.recordInTransaction(ENTITY, id, before.label(), AuditAction.DEACTIVATE,
                AuditSnapshot.of().field("Active", true, false), current, why);
    }

    @Transactional
    @PreAuthorize("hasAuthority('admin.branches')")
    public void reactivate(UUID id, BranchView current) {
        BranchRow before = require(id);
        if (before.active()) return;
        try {
            jdbc.sql("UPDATE branch SET is_active = TRUE WHERE id = :id")
                    .param("id", id, Types.OTHER)
                    .update();
        } catch (DataAccessException e) {
            throw refusedBy(id, before.label(), "Reactivate", e, current);
        }
        audit.recordInTransaction(ENTITY, id, before.label(), AuditAction.UPDATE,
                AuditSnapshot.of().field("Active", false, true), current, null);
    }

    // ---- helpers ---------------------------------------------------------

    private static AuditSnapshot snapshot(BranchForm before, BranchForm after) {
        return AuditSnapshot.of()
                .field("Code", before == null ? null : before.getCode(), after.getCode())
                .field("Name", before == null ? null : before.getName(), after.getName())
                .field("Type", before == null ? null : before.getBranchType().label(), after.getBranchType().label())
                .field("City", before == null ? null : before.getCity(), after.getCity())
                .field("Country", before == null ? null : before.getCountryCode(), after.getCountryCode())
                .field("Bonded", before == null ? null : before.isBonded(), after.isBonded())
                .field("Customs regime", before == null ? null : before.getCustomsRegime(),
                        after.isBonded() ? after.getCustomsRegime() : null);
    }

    /** A code, a name or the main branch already taken: the field to name, and why. */
    private static BranchTakenException taken(DuplicateKeyException e, BranchForm form) {
        String constraint = DbRefusal.constraint(e).orElse("");
        return switch (constraint) {
            case "branch_name_unique" -> new BranchTakenException("name",
                    "Another branch is already called " + form.getName() + ".");
            case "branch_one_main" -> new BranchTakenException("branchType",
                    "There is already a main branch: anyone with no branch of their own works there, so there is one.");
            default -> new BranchTakenException("code", "Another branch already has the code " + form.getCode() + ".");
        };
    }

    private ControlRefusedException refused(UUID id, String label, String attempt, BranchView current, String reason) {
        audit.recordRefusal(ENTITY, id, label, AuditSnapshot.of().value("Attempted", attempt), current, reason);
        return new ControlRefusedException(reason);
    }

    private RuntimeException refusedBy(UUID id, String label, String attempt, DataAccessException failure,
                                       BranchView current) {
        if (DbRefusal.isContention(failure)) return DbRefusal.asRefusal(failure);
        return DbRefusal.reason(failure)
                .<RuntimeException>map(reason -> refused(id, label, attempt, current, reason))
                .orElse(failure);
    }

    private BranchRow map(ResultSet rs, int rowNum) throws SQLException {
        return new BranchRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                BranchType.valueOf(rs.getString("branch_type")),
                rs.getString("city"),
                rs.getString("country_code"),
                rs.getBoolean("is_bonded"),
                rs.getString("customs_regime"),
                rs.getBoolean("is_active"),
                KigaliTime.read(rs, "created_at"),
                rs.getInt("locations"),
                rs.getInt("people"),
                rs.getBoolean("has_history"));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class BranchNotFoundException extends RuntimeException {
        public BranchNotFoundException(UUID id) {
            super("No branch with id " + id);
        }
    }

    /** A code, name or the one main branch someone else already has; names the form field. */
    public static class BranchTakenException extends RuntimeException {
        private final String field;

        public BranchTakenException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() { return field; }
    }
}
