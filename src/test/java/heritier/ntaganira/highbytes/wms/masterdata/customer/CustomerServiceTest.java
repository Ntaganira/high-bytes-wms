package heritier.ntaganira.highbytes.wms.masterdata.customer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.customer
 * - File       : CustomerServiceTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Customers are Finance's to manage and block, audited, and never deleted once delivered to
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DispatchLookupService;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerServiceTest extends IntegrationTest {

    @Autowired CustomerService customers;
    @Autowired DispatchLookupService lookups;
    @Autowired BranchService branches;

    private CustomerForm form(String code) {
        var form = new CustomerForm();
        form.setCode(code);
        form.setName("Ikirezi Construction");
        form.setMarket(CustomerMarket.EXPORT_GOMA);
        form.setCreditLimit(new BigDecimal("5000000"));
        form.setPaymentTermsDays(30);
        return form;
    }

    @Test
    void financeCreatesUpdatesBlocksAndDeactivatesACustomerAndEachStepIsAudited() {
        var branch = branches.findById(fx.kigali()).orElseThrow();
        fx.actAs(fx.user("fin", "FINANCE"));
        String code = "IKI-" + UUID.randomUUID().toString().substring(0, 6);

        UUID id = customers.create(form(code), branch);
        var created = customers.findById(id).orElseThrow();
        assertThat(created.code()).isEqualTo(code.toUpperCase());
        assertThat(created.market()).isEqualTo(CustomerMarket.EXPORT_GOMA);
        assertThat(created.state()).isEqualTo("ACTIVE");

        assertThatThrownBy(() -> customers.create(form(code), branch))
                .isInstanceOf(CustomerService.CustomerCodeTakenException.class);

        var edit = customers.formFor(id);
        edit.setName("Ikirezi Construction Ltd");
        customers.update(id, edit, branch);

        // A block needs a reason; with one, the customer leaves the delivery picker.
        assertThatThrownBy(() -> customers.setBlocked(id, true, " ", branch))
                .isInstanceOf(CustomerService.BlockNeedsReasonException.class);
        customers.setBlocked(id, true, "Unpaid invoices", branch);
        assertThat(customers.findById(id).orElseThrow().state()).isEqualTo("BLOCKED");
        assertThat(lookups.customers()).noneMatch(c -> c.id().equals(id));

        customers.setBlocked(id, false, null, branch);
        assertThat(lookups.customers()).anyMatch(c -> c.id().equals(id));

        customers.setActive(id, false, "Closed", branch);
        assertThat(customers.findById(id).orElseThrow().state()).isEqualTo("INACTIVE");
        assertThat(lookups.customers()).noneMatch(c -> c.id().equals(id));
        assertThat(customers.search(code, false)).isEmpty();
        assertThat(customers.search(code, true)).hasSize(1);

        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE entity_name = 'customer' AND entity_id = :id ORDER BY id")
                .param("id", id).query(String.class).list())
                .containsExactly("CREATE", "UPDATE", "UPDATE", "UPDATE", "DEACTIVATE");
    }

    @Test
    void theStoreAndTheAuthorizerCannotSetUpOrBlockCustomers() {
        var branch = branches.findById(fx.kigali()).orElseThrow();
        UUID id = fx.customer("guard", false);

        // Whoever authorizes deliveries picks from the list; they do not maintain it.
        fx.actAs(fx.user("dao", "WH_MANAGER"));
        assertThatThrownBy(() -> customers.create(form("X-" + UUID.randomUUID().toString().substring(0, 6)), branch))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> customers.setBlocked(id, true, "no", branch)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> customers.search(null, false)).isInstanceOf(AccessDeniedException.class);
    }
}
