package heritier.ntaganira.highbytes.wms.support;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.support
 * - File       : Fixtures.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Users, items, suppliers and bins for tests, created the way the database allows
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.security.AppUserDetailsService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

/**
 * Test data that respects V9: a role is granted by someone other than its
 * holder, from today, and the access checks run when each statement commits.
 *
 * <p>Nothing here weakens a control; it walks the same paths the application
 * does. Names carry a random suffix so test classes never collide.
 */
public class Fixtures {

    private final JdbcClient jdbc;
    private final AppUserDetailsService userDetails;

    public Fixtures(JdbcClient jdbc, AppUserDetailsService userDetails) {
        this.jdbc = jdbc;
        this.userDetails = userDetails;
    }

    public UUID branch(String code) {
        return jdbc.sql("SELECT id FROM branch WHERE code = :code").param("code", code)
                .query(UUID.class).single();
    }

    public UUID kigali() {
        return branch("KGL");
    }

    public UUID location(String code) {
        return jdbc.sql("SELECT id FROM location WHERE code = :code").param("code", code)
                .query(UUID.class).single();
    }

    public UUID uom(String code) {
        return jdbc.sql("SELECT id FROM uom WHERE code = :code").param("code", code)
                .query(UUID.class).single();
    }

    private UUID admin() {
        return jdbc.sql("SELECT id FROM app_user WHERE username = 'admin'").query(UUID.class).single();
    }

    /** An active user holding exactly these roles at the given branch. */
    public UUID userAt(String branchCode, String prefix, String... roleCodes) {
        UUID branch = branch(branchCode);
        UUID id = UUID.randomUUID();
        String username = prefix + "-" + id.toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO app_user (id, username, full_name, password_hash, home_branch_id, is_active, must_change_password)
                VALUES (:id, :username, :name, 'x', :branch, TRUE, FALSE)
                """)
                .param("id", id, Types.OTHER)
                .param("username", username)
                .param("name", "Test " + username)
                .param("branch", branch, Types.OTHER)
                .update();
        for (String role : roleCodes) {
            jdbc.sql("""
                    INSERT INTO user_role (user_id, role_id, branch_id, valid_from, assigned_by)
                    SELECT :user, r.id, :branch, CURRENT_DATE, :admin FROM role r WHERE r.code = :role
                    """)
                    .param("user", id, Types.OTHER)
                    .param("branch", branch, Types.OTHER)
                    .param("admin", admin(), Types.OTHER)
                    .param("role", role)
                    .update();
        }
        return id;
    }

    public UUID user(String prefix, String... roleCodes) {
        return userAt("KGL", prefix, roleCodes);
    }

    /** Signs the user in for the calling thread, as the application would load them. */
    public AppUserDetails actAs(UUID userId) {
        return actAs(userId, kigali());
    }

    public AppUserDetails actAs(UUID userId, UUID branchId) {
        AppUserDetails details = userDetails.reload(userId, branchId).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return details;
    }

    public UUID item(String prefix, String productType, String baseUom, BigDecimal thicknessMm) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO item (id, item_code, description, product_type, thickness_mm, base_uom_id)
                VALUES (:id, :code, :description, :type, :thickness, :uom)
                """)
                .param("id", id, Types.OTHER)
                .param("code", (prefix + "-" + id.toString().substring(0, 8)).toUpperCase())
                .param("description", "Test item " + prefix)
                .param("type", productType)
                .param("thickness", thicknessMm)
                .param("uom", uom(baseUom), Types.OTHER)
                .update();
        return id;
    }

    public void conversion(UUID itemId, String uomCode, String factorToBase) {
        jdbc.sql("INSERT INTO item_uom_conversion (item_id, uom_id, factor_to_base) VALUES (:item, :uom, :factor)")
                .param("item", itemId, Types.OTHER)
                .param("uom", uom(uomCode), Types.OTHER)
                .param("factor", new BigDecimal(factorToBase))
                .update();
    }

    public UUID supplier(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO supplier (id, code, name) VALUES (:id, :code, :name)")
                .param("id", id, Types.OTHER)
                .param("code", ("S-" + id.toString().substring(0, 8)).toUpperCase())
                .param("name", "Supplier " + prefix)
                .update();
        return id;
    }

    public UUID bin(String locationCode, String binCode) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO storage_bin (id, location_id, bin_code) VALUES (:id, :location, :code)")
                .param("id", id, Types.OTHER)
                .param("location", location(locationCode), Types.OTHER)
                .param("code", binCode + "-" + id.toString().substring(0, 4).toUpperCase())
                .update();
        return id;
    }

    /**
     * The role codes of the chain a receipt raised today binds to, in signing
     * order. Read from the workflow definition in force, so the test follows
     * whichever chain applies on the day it runs.
     */
    public List<String> receiptChainRoles() {
        return jdbc.sql("""
                SELECT r.code
                  FROM workflow_definition wd
                  JOIN document_type dt ON dt.id = wd.document_type_id AND dt.code = 'GRN'
                  JOIN workflow_step ws ON ws.workflow_definition_id = wd.id
                  JOIN role r ON r.id = ws.required_role_id
                 WHERE kigali_today() >= wd.effective_from
                   AND (wd.effective_to IS NULL OR kigali_today() < wd.effective_to)
                 ORDER BY ws.sequence_no
                """)
                .query(String.class).list();
    }
}
