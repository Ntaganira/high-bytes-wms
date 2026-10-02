package heritier.ntaganira.highbytes.wms.admin.audit;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.audit
 * - File       : AuditLogTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The audit trail searched: each reader sees as far as their right reaches, and no further
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditEntry;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a real PostgreSQL 16 with Flyway V1 to V17. Entries are written as the services write them. */
class AuditLogTest extends IntegrationTest {

    @Autowired AuditService audit;
    @Autowired BranchService branches;

    BranchView kigali;
    BranchView rubavu;
    UUID record;
    String tag;

    @BeforeEach
    void entries() {
        kigali = branches.findById(fx.kigali()).orElseThrow();
        rubavu = branches.findById(fx.branch("RBV")).orElseThrow();
        record = UUID.randomUUID();
        tag = "Audit test " + record.toString().substring(0, 8);
        audit.record("item", record, tag + " at Kigali", AuditAction.UPDATE,
                AuditSnapshot.of().field("Name", "Before", "After"), kigali, "50% off_cut");
        audit.record("item", record, tag + " at Rubavu", AuditAction.UPDATE,
                AuditSnapshot.of().field("Name", "After", "Again"), rubavu, null);
        audit.record("item", record, tag + " nowhere", AuditAction.UPDATE, AuditSnapshot.of(), null, null);
    }

    private AuditService.Scope scopeOf(UUID user) {
        return AuditService.Scope.of(userDetails.reload(user, fx.kigali()).orElseThrow(), "audit.view");
    }

    private List<String> labels(List<AuditEntry> entries) {
        return entries.stream().map(AuditEntry::entityLabel).toList();
    }

    /** A user holding the role at every branch: a grant with no branch. */
    private UUID everywhere(String role) {
        UUID id = fx.user("all");
        jdbc.sql("""
                INSERT INTO user_role (user_id, role_id, branch_id, valid_from, assigned_by)
                SELECT :u, r.id, NULL, CURRENT_DATE, (SELECT id FROM app_user WHERE username = 'admin')
                  FROM role r WHERE r.code = :role
                """).param("u", id).param("role", role).update();
        return id;
    }

    @Test
    void aRightHeldAtOneBranchReadsThatBranchsEntriesAlone() {
        var kigaliOnly = scopeOf(fx.user("ic", "INTERNAL_CTRL"));
        var found = audit.search(AuditService.Query.entity("item", record), kigaliOnly, 50);
        assertThat(labels(found)).containsExactly(tag + " at Kigali");

        // Recorded elsewhere, or at no branch: not there, and not found by its number either.
        long rubavuEntry = jdbc.sql("SELECT id FROM audit_log WHERE entity_label = :l").param("l", tag + " at Rubavu")
                .query(Long.class).single();
        assertThat(audit.find(rubavuEntry, kigaliOnly)).isEmpty();

        var all = scopeOf(everywhere("INTERNAL_CTRL"));
        assertThat(labels(audit.search(AuditService.Query.entity("item", record), all, 50)))
                .containsExactly(tag + " nowhere", tag + " at Rubavu", tag + " at Kigali");
        assertThat(audit.find(rubavuEntry, all)).isPresent();

        // No right at all reads nothing.
        var none = scopeOf(fx.user("whm", "WH_MANAGER"));
        assertThat(audit.search(AuditService.Query.entity("item", record), none, 50)).isEmpty();
    }

    @Test
    void theTrailIsSearchedByWordsTakenLiterallyByDayAndPagedBackwards() {
        var all = scopeOf(everywhere("INTERNAL_CTRL"));
        LocalDate today = jdbc.sql("SELECT kigali_today()").query(LocalDate.class).single();

        // The reader's % and _ are characters, not wildcards.
        var words = new AuditService.Query(null, null, null, "item", record, null, null, "50% off_cut", null);
        assertThat(labels(audit.search(words, all, 50))).containsExactly(tag + " at Kigali");
        var wildcard = new AuditService.Query(null, null, null, "item", record, null, null, "50%cut", null);
        assertThat(labels(audit.search(wildcard, all, 50))).doesNotContain(tag + " at Kigali");

        var todayOnly = new AuditService.Query(today, today, "UPDATE", "item", record, null, null, null, null);
        assertThat(audit.search(todayOnly, all, 50)).hasSize(3);
        var yesterday = new AuditService.Query(today.minusDays(1), today.minusDays(1), null, "item", record, null, null, null, null);
        assertThat(audit.search(yesterday, all, 50)).isEmpty();

        var firstPage = audit.search(AuditService.Query.entity("item", record), all, 2);
        assertThat(firstPage).hasSize(2);
        long last = firstPage.get(1).id();
        var older = new AuditService.Query(null, null, null, "item", record, null, null, null, last);
        assertThat(labels(audit.search(older, all, 2))).containsExactly(tag + " at Kigali");

        var atRubavu = new AuditService.Query(null, null, null, "item", record, null, rubavu.id(), null, null);
        assertThat(labels(audit.search(atRubavu, all, 50))).containsExactly(tag + " at Rubavu");
    }

    @Test
    void anEntryReadsItsChangesAndItsTimeInKigali() {
        var all = scopeOf(everywhere("INTERNAL_CTRL"));
        AuditEntry kigaliEntry = audit.search(AuditService.Query.entity("item", record), all, 50).get(2);
        assertThat(kigaliEntry.changes()).singleElement().satisfies(c -> {
            assertThat(c.before()).isEqualTo("Before");
            assertThat(c.after()).isEqualTo("After");
        });
        assertThat(kigaliEntry.actorName()).isEqualTo("System");
        LocalDate stored = jdbc.sql("SELECT (occurred_at AT TIME ZONE 'Africa/Kigali')::date FROM audit_log WHERE id = :id")
                .param("id", kigaliEntry.id()).query(LocalDate.class).single();
        assertThat(kigaliEntry.occurredAt().toLocalDate()).isEqualTo(stored);
    }
}
