package heritier.ntaganira.highbytes.wms.admin.branch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.branch
 * - File       : BranchAdminTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Branches as configuration: created, amended, closed only when finished with, never renamed in serials
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.support.CloseFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a real PostgreSQL 16 with Flyway V1 to V17: the database holds the rules, this class names the field. */
class BranchAdminTest extends IntegrationTest {

    @Autowired BranchAdminService admin;
    @Autowired BranchService branchReads;
    @Autowired PlatformTransactionManager transactions;

    UUID administrator;
    BranchView kigali;

    @BeforeEach
    void signIn() {
        administrator = fx.user("adm", "SYS_ADMIN");
        kigali = branchReads.findById(fx.kigali()).orElseThrow();
        fx.actAs(administrator);
    }

    /** A code no other test has used: a letter, then up to five letters and digits. */
    private static String freshCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var r = ThreadLocalRandom.current();
        var sb = new StringBuilder("T");
        for (int i = 0; i < 5; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    private BranchForm form(String code) {
        var f = new BranchForm();
        f.setCode(code);
        f.setName("Test branch " + code);
        f.setBranchType(BranchType.BRANCH);
        f.setCity("Musanze");
        f.setCountryCode("RW");
        return f;
    }

    @Test
    void aBranchIsCreatedAmendedAndItsCodeNeverChanges() {
        String code = freshCode();
        UUID id = admin.create(form(code), kigali);

        BranchRow created = admin.require(id);
        assertThat(created.active()).isTrue();
        assertThat(created.hasHistory()).isFalse();
        assertThat(admin.list()).extracting(BranchRow::code).contains(code);

        // Nothing has happened there yet: its kind may still change. The code posted is ignored.
        var edit = admin.formFor(id);
        edit.setCode("XXXX");
        edit.setName("Test branch " + code + " Bonded");
        edit.setBranchType(BranchType.BONDED);
        edit.setBonded(true);
        edit.setCustomsRegime("Public bonded warehouse");
        admin.update(id, edit, kigali);

        BranchRow after = admin.require(id);
        assertThat(after.code()).isEqualTo(code);
        assertThat(after.branchType()).isEqualTo(BranchType.BONDED);
        assertThat(after.customsRegime()).isEqualTo("Public bonded warehouse");

        // Whatever path writes, the database keeps the code.
        assertThatThrownBy(() -> jdbc.sql("UPDATE branch SET code = 'ZZZZ' WHERE id = :id").param("id", id).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("code never changes");

        String me = userDetails.reload(administrator, fx.kigali()).orElseThrow().username();
        assertThat(jdbc.sql("SELECT action || ' ' || actor_username FROM audit_log WHERE entity_name = 'branch' AND entity_id = :id")
                .param("id", id).query(String.class).list())
                .containsExactlyInAnyOrder("CREATE " + me, "UPDATE " + me);
    }

    @Test
    void aNameOrCodeTakenAndASecondMainBranchAreRefusedOnTheirField() {
        String code = freshCode();
        admin.create(form(code), kigali);

        var sameCode = form(code);
        sameCode.setName("Another name " + code);
        assertThatThrownBy(() -> admin.create(sameCode, kigali))
                .isInstanceOfSatisfying(BranchAdminService.BranchTakenException.class, e -> assertThat(e.field()).isEqualTo("code"));

        var sameName = form(freshCode());
        sameName.setName(("Test branch " + code).toUpperCase());
        assertThatThrownBy(() -> admin.create(sameName, kigali))
                .isInstanceOfSatisfying(BranchAdminService.BranchTakenException.class, e -> assertThat(e.field()).isEqualTo("name"));

        var secondMain = form(freshCode());
        secondMain.setBranchType(BranchType.MAIN);
        assertThatThrownBy(() -> admin.create(secondMain, kigali))
                .isInstanceOfSatisfying(BranchAdminService.BranchTakenException.class, e -> assertThat(e.field()).isEqualTo("branchType"));
    }

    @Test
    void aBranchClosesOnlyWithAReasonAndNothingNewStartsThere() {
        UUID id = admin.create(form(freshCode()), kigali);

        assertThatThrownBy(() -> admin.deactivate(id, "  ", kigali))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("Say why");
        assertThat(admin.deactivationBlocker(id)).isNull();
        admin.deactivate(id, "Lease not renewed", kigali);
        assertThat(admin.require(id).active()).isFalse();
        assertThat(jdbc.sql("SELECT reason FROM audit_log WHERE entity_id = :id AND action = 'DEACTIVATE'")
                .param("id", id).query(String.class).single()).isEqualTo("Lease not renewed");

        // Nothing new starts at a closed branch, whatever path writes.
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO location (branch_id, code, name, location_type)
                VALUES (:b, :code, 'Store', 'WAREHOUSE')
                """).param("b", id).param("code", "LOC-" + UUID.randomUUID().toString().substring(0, 6)).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("is not active");
        assertThat(branchReads.findAll()).extracting(BranchView::id).doesNotContain(id);

        admin.reactivate(id, kigali);
        assertThat(admin.require(id).active()).isTrue();
    }

    @Test
    void theMainBranchAndABranchHoldingStockStayOpenAndAPastFixesTheKind() {
        assertThat(admin.deactivationBlocker(fx.kigali())).contains("main branch");
        assertThatThrownBy(() -> admin.deactivate(fx.kigali(), "Testing", kigali))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("main branch");

        var cf = new CloseFlow(fx, jdbc, transactions);
        cf.stockOn(cf.today().minusDays(2), 4);
        fx.actAs(administrator);
        // Names where, never how much: the book of a count is on no page.
        assertThat(admin.deactivationBlocker(cf.branch)).contains("still holds stock").doesNotContain("4.000");
        assertThatThrownBy(() -> admin.deactivate(cf.branch, "Testing", kigali))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("still holds stock");

        var edit = admin.formFor(cf.branch);
        assertThat(edit.isHasHistory()).isTrue();
        edit.setBranchType(BranchType.BONDED);
        edit.setBonded(true);
        edit.setCustomsRegime("Public bonded warehouse");
        assertThatThrownBy(() -> admin.update(cf.branch, edit, kigali))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("are fixed");
    }

    @Test
    void onlyAnAdministratorOfBranchesChangesThem() {
        fx.actAs(fx.user("whm", "WH_MANAGER"));
        assertThatThrownBy(() -> admin.create(form(freshCode()), kigali)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> admin.deactivate(fx.kigali(), "Mine", kigali)).isInstanceOf(AccessDeniedException.class);
    }
}
