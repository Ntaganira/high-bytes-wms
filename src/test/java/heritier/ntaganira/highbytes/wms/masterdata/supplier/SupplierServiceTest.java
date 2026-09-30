package heritier.ntaganira.highbytes.wms.masterdata.supplier;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.supplier
 * - File       : SupplierServiceTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Suppliers are Finance's to manage, audited, and never deleted once received from
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.support.GrnFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierServiceTest extends IntegrationTest {

    @Autowired SupplierService suppliers;
    @Autowired ReceivingService receiving;
    @Autowired BranchService branches;

    private SupplierForm form(String code) {
        var form = new SupplierForm();
        form.setCode(code);
        form.setName("Guangzhou Glass Co");
        form.setCountryCode("CN");
        form.setTin("123456789");
        return form;
    }

    @Test
    void financeCreatesUpdatesAndDeactivatesASupplierAndEachStepIsAudited() {
        var branch = branches.findById(fx.kigali()).orElseThrow();
        fx.actAs(fx.user("fin", "FINANCE"));
        String code = "GGC-" + UUID.randomUUID().toString().substring(0, 6);

        UUID id = suppliers.create(form(code), branch);
        var created = suppliers.findById(id).orElseThrow();
        assertThat(created.foreign()).as("a supplier outside RW is foreign, by the country").isTrue();
        assertThat(created.code()).isEqualTo(code.toUpperCase());

        assertThatThrownBy(() -> suppliers.create(form(code), branch))
                .isInstanceOf(SupplierService.SupplierCodeTakenException.class);

        var edit = suppliers.formFor(id);
        edit.setName("Guangzhou Glass Company Ltd");
        suppliers.update(id, edit, branch);
        suppliers.setActive(id, false, "Contract ended", branch);
        assertThat(suppliers.findById(id).orElseThrow().active()).isFalse();
        assertThat(suppliers.search(code, false)).isEmpty();
        assertThat(suppliers.search(code, true)).hasSize(1);

        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE entity_name = 'supplier' AND entity_id = :id ORDER BY id")
                .param("id", id).query(String.class).list())
                .containsExactly("CREATE", "UPDATE", "DEACTIVATE");
    }

    @Test
    void theStoreCannotSetUpSuppliersAndAnInactiveSupplierCannotBeReceivedFrom() {
        var branch = branches.findById(fx.kigali()).orElseThrow();
        GrnFlow flow = new GrnFlow(fx, receiving);

        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> suppliers.create(form("X-" + UUID.randomUUID().toString().substring(0, 6)), branch))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> suppliers.search(null, false)).isInstanceOf(AccessDeniedException.class);

        // Finance deactivates the supplier; a new receipt naming it is refused, with the reason.
        fx.actAs(flow.poster);
        suppliers.setActive(flow.supplier, false, "Blocked", branch);
        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> receiving.create(flow.standardForm()))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("deactivated");
    }
}
