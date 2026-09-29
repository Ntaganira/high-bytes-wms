package heritier.ntaganira.highbytes.wms.branch;

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
