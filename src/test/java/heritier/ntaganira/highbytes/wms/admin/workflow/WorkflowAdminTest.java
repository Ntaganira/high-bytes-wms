package heritier.ntaganira.highbytes.wms.admin.workflow;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.workflow
 * - File       : WorkflowAdminTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The chains read whole; only a switchover still to come moves, both chains together, with a reason
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Against a real PostgreSQL 16 with Flyway V1 to V17. Every date moved here is put back. */
class WorkflowAdminTest extends IntegrationTest {

    static final LocalDate SWITCH = LocalDate.of(2027, 1, 1);

    @Autowired WorkflowService workflows;
    @Autowired BranchService branches;

    BranchView kigali;
    LocalDate today;

    @BeforeEach
    void signIn() {
        fx.actAs(fx.user("adm", "SYS_ADMIN"));
        kigali = branches.findById(fx.kigali()).orElseThrow();
        today = jdbc.sql("SELECT kigali_today()").query(LocalDate.class).single();
    }

    private UUID definition(String type, String basis) {
        return jdbc.sql("""
                SELECT wd.id FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
                 WHERE dt.code = :type AND wd.basis = :basis
                """).param("type", type).param("basis", basis).query(UUID.class).single();
    }

    private LocalDate startOf(UUID def) {
        return jdbc.sql("SELECT effective_from FROM workflow_definition WHERE id = :id").param("id", def)
                .query(LocalDate.class).single();
    }

    private LocalDate endOf(UUID def) {
        return jdbc.sql("SELECT effective_to FROM workflow_definition WHERE id = :id").param("id", def)
                .query(LocalDate.class).optional().orElse(null);
    }

    @Test
    void everyChainIsReadWholeWithItsSwitchover() {
        var receipts = workflows.chains().stream().filter(c -> c.typeCode().equals("GRN")).findFirst().orElseThrow();
        assertThat(receipts.versions()).hasSize(2);
        assertThat(receipts.versions().get(0).steps()).hasSize(3);
        assertThat(receipts.versions().get(1).steps()).hasSize(4);
        assertThat(receipts.versions().get(0).boundCount()).isNotNegative();
        assertThat(receipts.switchovers()).singleElement().satisfies(s -> {
            assertThat(s.date()).isEqualTo(SWITCH);
            assertThat(s.movable()).isEqualTo(today.isBefore(SWITCH));
        });

        var cutting = workflows.chains().stream().filter(c -> c.typeCode().equals("CUT")).findFirst().orElseThrow();
        assertThat(cutting.switchovers()).isEmpty();
    }

    @Test
    void aSwitchoverStillToComeMovesBothChainsTogetherAndIsRecordedWithItsReason() {
        assumeTrue(today.isBefore(SWITCH.minusDays(1)), "the 2027 switch is too close to move");
        UUID old2026 = definition("GRN", "POLICY_2026");
        UUID new2027 = definition("GRN", "RESTRUCTURE_2027");
        try {
            workflows.moveSwitchover(old2026, SWITCH.plusMonths(1), "The Board deferred the restructure", kigali);
            assertThat(endOf(old2026)).isEqualTo(SWITCH.plusMonths(1));
            assertThat(startOf(new2027)).isEqualTo(SWITCH.plusMonths(1));

            // Earlier than first planned, still to come: the old chain gives way first.
            workflows.moveSwitchover(old2026, SWITCH.minusDays(7), "Brought forward by the Board", kigali);
            assertThat(endOf(old2026)).isEqualTo(SWITCH.minusDays(7));
            assertThat(startOf(new2027)).isEqualTo(SWITCH.minusDays(7));

            assertThat(workflows.history(10)).anySatisfy(e -> {
                assertThat(e.reason()).isEqualTo("Brought forward by the Board");
                assertThat(e.changes()).extracting(c -> c.fieldLabel()).contains("First day in force");
            });
        } finally {
            workflows.moveSwitchover(old2026, SWITCH, "Put back where the Board put it", kigali);
        }
        assertThat(endOf(old2026)).isEqualTo(SWITCH);
        assertThat(startOf(new2027)).isEqualTo(SWITCH);
    }

    @Test
    void aSwitchoverMovesOnlyForward_withAReason_andNeverForADayBegun() {
        assumeTrue(today.isBefore(SWITCH.minusDays(1)), "the 2027 switch is too close to move");
        UUID old2026 = definition("GRN", "POLICY_2026");

        assertThatThrownBy(() -> workflows.moveSwitchover(old2026, SWITCH.plusDays(3), " ", kigali))
                .isInstanceOf(ControlRefusedException.class).hasMessageContaining("Say why");
        assertThatThrownBy(() -> workflows.moveSwitchover(old2026, today, "Now", kigali))
                .isInstanceOf(ControlRefusedException.class).hasMessageContaining("day already begun");
        assertThatThrownBy(() -> workflows.moveSwitchover(old2026, today.minusDays(30), "Back-date", kigali))
                .isInstanceOf(ControlRefusedException.class).hasMessageContaining("day already begun");
        // The last version gives way to none: nothing to move.
        assertThatThrownBy(() -> workflows.moveSwitchover(definition("GRN", "RESTRUCTURE_2027"), SWITCH.plusYears(1), "End it", kigali))
                .isInstanceOf(ControlRefusedException.class).hasMessageContaining("gives way to no other");
        assertThat(endOf(old2026)).isEqualTo(SWITCH);

        // Not from a screen, not from anywhere: the database refuses a date already begun, and a lone move.
        assertThatThrownBy(() -> jdbc.sql("UPDATE workflow_definition SET effective_to = kigali_today() WHERE id = :id")
                .param("id", old2026).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("day already begun");
    }

    @Test
    void aChainsStepsChangeOnlyByMigrationAndNeverOnceBound() {
        UUID receipts2026 = definition("GRN", "POLICY_2026");
        assertThatThrownBy(() -> jdbc.sql("UPDATE workflow_step SET action_label = 'VERIFY' WHERE workflow_definition_id = :id AND sequence_no = 3")
                .param("id", definition("CUT", "POLICY_2026")).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("reviewed migration");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM workflow_step WHERE workflow_definition_id = :id AND sequence_no = 1")
                .param("id", receipts2026).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE workflow_definition SET notes = 'Rewritten' WHERE id = :id")
                .param("id", receipts2026).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Only the dates");
    }

    @Test
    void onlyTheWorkflowAdministratorMovesASwitchover() {
        fx.actAs(fx.user("ic", "INTERNAL_CTRL"));
        assertThatThrownBy(() -> workflows.moveSwitchover(definition("GRN", "POLICY_2026"), SWITCH.plusDays(5), "Mine", kigali))
                .isInstanceOf(AccessDeniedException.class);
    }
}
