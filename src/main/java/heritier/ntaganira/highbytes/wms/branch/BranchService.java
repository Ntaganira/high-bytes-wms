package heritier.ntaganira.highbytes.wms.branch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.branch
 * - File       : BranchService.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Reads branches: all, by id, and the default for a user's home branch
 * </pre>
 */

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class BranchService {

    private static final String ALL = """
            SELECT id, code, name, is_bonded, branch_type
              FROM branch
             WHERE is_active
             ORDER BY branch_type, name
            """;

    private static final String BY_ID = """
            SELECT id, code, name, is_bonded, branch_type
              FROM branch
             WHERE id = :id AND is_active
            """;

    private final JdbcClient jdbc;

    public BranchService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BranchView> findAll() {
        return jdbc.sql(ALL).query(this::map).list();
    }

    /** Every branch there has been, active or not: for reading history, never for working in. */
    public List<BranchView> everyBranch() {
        return jdbc.sql("""
                SELECT id, code, name, is_bonded, branch_type
                  FROM branch
                 ORDER BY is_active DESC, branch_type, name
                """).query(this::map).list();
    }

    public Optional<BranchView> findById(UUID id) {
        if (id == null) return Optional.empty();
        return jdbc.sql(BY_ID).param("id", id).query(this::map).optional();
    }

    /** The branch a user lands on: their home branch, else the main one. */
    public Optional<BranchView> defaultFor(UUID homeBranchId) {
        return findById(homeBranchId).or(() ->
                findAll().stream().filter(b -> "MAIN".equals(b.branchType())).findFirst());
    }

    private BranchView map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new BranchView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("is_bonded"),
                rs.getString("branch_type"));
    }
}
