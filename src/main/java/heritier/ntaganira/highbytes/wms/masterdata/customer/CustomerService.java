package heritier.ntaganira.highbytes.wms.masterdata.customer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.customer
 * - File       : CustomerService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The customer master: search, create, update, deactivate, block and unblock, audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The customer master.
 *
 * <p>Customers are set up and changed by whoever holds {@code partner.manage}
 * (Finance), not by whoever raises a delivery authorization for them: the
 * sales side picks from this list and cannot invent a customer or unblock one.
 * A customer that has been delivered to is deactivated, never deleted.
 *
 * <p>Blocking is separate from deactivating. A blocked customer is still a
 * customer, with history and open documents; it simply may not be authorized
 * a new delivery until Finance lifts the block, and the database refuses a
 * delivery authorization naming one.
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

    private static final String SELECT = """
            SELECT c.id, c.code, c.name, c.tin, c.market, c.address, c.phone, c.email, c.credit_limit,
                   c.payment_terms_days, c.is_blocked, c.is_active,
                   (SELECT COUNT(*) FROM delivery_authorization a WHERE a.customer_id = c.id)::int
                        AS authorization_count
              FROM customer c
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public CustomerService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- reads -----------------------------------------------------------

    @PreAuthorize("hasAuthority('partner.manage')")
    public List<CustomerRow> search(String query, boolean includeInactive) {
        // A nullable parameter tested with IS NULL carries a cast: PostgreSQL cannot type a bare NULL.
        return jdbc.sql(SELECT + """
                 WHERE (:query::text IS NULL OR c.code ILIKE :like OR c.name ILIKE :like OR c.tin ILIKE :like)
                   AND (:includeInactive OR c.is_active)
                 ORDER BY c.is_active DESC, c.name
                 LIMIT 300
                """)
                .param("query", blankToNull(query))
                .param("like", query == null ? null : "%" + query.trim() + "%")
                .param("includeInactive", includeInactive)
                .query(this::map)
                .list();
    }

    @PreAuthorize("hasAuthority('partner.manage')")
    public Optional<CustomerRow> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id")
                .param("id", id, Types.OTHER)
                .query(this::map)
                .optional();
    }

    @PreAuthorize("hasAuthority('partner.manage')")
    public CustomerForm formFor(UUID id) {
        var row = findById(id).orElseThrow(() -> new CustomerNotFoundException(id));
        var f = new CustomerForm();
        f.setId(row.id());
        f.setCode(row.code());
        f.setName(row.name());
        f.setTin(row.tin());
        f.setMarket(row.market());
        f.setAddress(row.address());
        f.setPhone(row.phone());
        f.setEmail(row.email());
        f.setCreditLimit(row.creditLimit());
        f.setPaymentTermsDays(row.paymentTermsDays());
        f.setActive(row.active());
        return f;
    }

    // ---- writes ----------------------------------------------------------

    @Transactional
    @PreAuthorize("hasAuthority('partner.manage')")
    public UUID create(CustomerForm form, BranchView branch) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO customer (id, code, name, tin, market, address, phone, email, credit_limit,
                                          payment_terms_days, is_active)
                    VALUES (:id, :code, :name, :tin, :market, :address, :phone, :email, :creditLimit,
                            :terms, :active)
                    """)
                    .param("id", id, Types.OTHER)
                    .params(columns(form))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new CustomerCodeTakenException(form.getCode());
        }
        audit.recordInTransaction("customer", id, "Customer · " + form.getCode(), AuditAction.CREATE,
                snapshotOf(null, form), branch, null);
        return id;
    }

    @Transactional
    @PreAuthorize("hasAuthority('partner.manage')")
    public void update(UUID id, CustomerForm form, BranchView branch) {
        CustomerForm before = formFor(id);
        try {
            jdbc.sql("""
                    UPDATE customer
                       SET code = :code, name = :name, tin = :tin, market = :market, address = :address,
                           phone = :phone, email = :email, credit_limit = :creditLimit,
                           payment_terms_days = :terms, is_active = :active, updated_at = now()
                     WHERE id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .params(columns(form))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new CustomerCodeTakenException(form.getCode());
        }
        var snapshot = snapshotOf(before, form);
        if (!snapshot.unchanged()) {
            audit.recordInTransaction("customer", id, "Customer · " + form.getCode(),
                    form.isActive() ? AuditAction.UPDATE : AuditAction.DEACTIVATE, snapshot, branch, null);
        }
    }

    /** Deactivation, never deletion: past authorizations keep pointing at the customer. */
    @Transactional
    @PreAuthorize("hasAuthority('partner.manage')")
    public void setActive(UUID id, boolean active, String reason, BranchView branch) {
        CustomerForm before = formFor(id);
        if (before.isActive() == active) return;
        jdbc.sql("UPDATE customer SET is_active = :active, updated_at = now() WHERE id = :id")
                .param("id", id, Types.OTHER)
                .param("active", active)
                .update();
        audit.recordInTransaction("customer", id, "Customer · " + before.getCode(),
                active ? AuditAction.UPDATE : AuditAction.DEACTIVATE,
                AuditSnapshot.of().field("Active", before.isActive(), active), branch, reason);
    }

    /**
     * Blocks or unblocks a customer. A block needs a reason, because it is the
     * answer to "why was this delivery refused"; lifting one is Finance's
     * decision too, and is recorded the same way.
     */
    @Transactional
    @PreAuthorize("hasAuthority('partner.manage')")
    public void setBlocked(UUID id, boolean blocked, String reason, BranchView branch) {
        CustomerRow before = findById(id).orElseThrow(() -> new CustomerNotFoundException(id));
        if (before.blocked() == blocked) return;
        if (blocked && (reason == null || reason.isBlank())) {
            throw new BlockNeedsReasonException();
        }
        jdbc.sql("UPDATE customer SET is_blocked = :blocked, updated_at = now() WHERE id = :id")
                .param("id", id, Types.OTHER)
                .param("blocked", blocked)
                .update();
        audit.recordInTransaction("customer", id, "Customer · " + before.code(), AuditAction.UPDATE,
                AuditSnapshot.of().field("Blocked", before.blocked(), blocked), branch,
                reason == null || reason.isBlank() ? null : reason.trim());
    }

    // ---- helpers ---------------------------------------------------------

    private Map<String, Object> columns(CustomerForm form) {
        var m = new HashMap<String, Object>();
        m.put("code", form.getCode());
        m.put("name", form.getName());
        m.put("tin", form.getTin());
        m.put("market", form.getMarket().name());
        m.put("address", form.getAddress());
        m.put("phone", form.getPhone());
        m.put("email", form.getEmail());
        m.put("creditLimit", form.getCreditLimit());
        m.put("terms", form.getPaymentTermsDays());
        m.put("active", form.isActive());
        return m;
    }

    private AuditSnapshot snapshotOf(CustomerForm before, CustomerForm after) {
        return AuditSnapshot.of()
                .field("Code",    before == null ? null : before.getCode(),     after.getCode())
                .field("Name",    before == null ? null : before.getName(),     after.getName())
                .field("TIN",     before == null ? null : before.getTin(),      after.getTin())
                .field("Market",  before == null ? null : before.getMarket(),   after.getMarket())
                .field("Address", before == null ? null : before.getAddress(),  after.getAddress())
                .field("Phone",   before == null ? null : before.getPhone(),    after.getPhone())
                .field("Email",   before == null ? null : before.getEmail(),    after.getEmail())
                .field("Credit limit", before == null ? null : before.getCreditLimit(), after.getCreditLimit())
                .field("Payment terms (days)", before == null ? null : before.getPaymentTermsDays(),
                        after.getPaymentTermsDays())
                .field("Active",  before == null ? null : before.isActive(),    after.isActive());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private CustomerRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        int terms = rs.getInt("payment_terms_days");
        return new CustomerRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("tin"),
                CustomerMarket.valueOf(rs.getString("market")),
                rs.getString("address"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getBigDecimal("credit_limit"),
                rs.wasNull() ? null : terms,
                rs.getBoolean("is_blocked"),
                rs.getBoolean("is_active"),
                rs.getInt("authorization_count"));
    }

    // ---- failures the user should see, not a stack trace -------------------

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class CustomerNotFoundException extends RuntimeException {
        public CustomerNotFoundException(UUID id) {
            super("No customer with id " + id);
        }
    }

    public static class CustomerCodeTakenException extends RuntimeException {
        public CustomerCodeTakenException(String code) {
            super("Customer code " + code + " is already in use.");
        }
    }

    public static class BlockNeedsReasonException extends RuntimeException {
        public BlockNeedsReasonException() {
            super("Blocking a customer needs a reason: it is what explains a refused delivery.");
        }
    }
}
