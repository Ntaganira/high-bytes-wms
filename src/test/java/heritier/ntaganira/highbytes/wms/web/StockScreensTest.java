package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : StockScreensTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The stock screens render for whoever holds stock.view, and show no book of a place being counted
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.count.CountScope;
import heritier.ntaganira.highbytes.wms.inventory.count.CountService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.CountFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** As a Warehouse Manager (stock.view at Gahanga) and a salesperson (no stock right: 403). */
class StockScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired CountService counts;
    @Autowired ReceivingService receiving;

    CountFlow cf;
    AppUserDetails manager;
    AppUserDetails salesperson;

    @BeforeEach
    void stock() {
        cf = new CountFlow(fx, counts, receiving);
        cf.stock("10", true, "4");
        manager = userDetails.reload(fx.user("whm", "WH_MANAGER"), fx.kigali()).orElseThrow();
        salesperson = userDetails.reload(fx.user("sales", "SALES"), fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    private String glassCode() {
        return jdbc.sql("SELECT item_code FROM item WHERE id = :id").param("id", cf.grn.glass).query(String.class).single();
    }

    @Test
    void balancesAnItemAndTheLedgerRender() throws Exception {
        mvc.perform(as(manager, get("/stock").param("location", cf.location.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(glassCode())))
                .andExpect(content().string(containsString("80,000.00")));
        mvc.perform(as(manager, get("/stock").param("filter", "below-reorder").param("type", "GLASS")))
                .andExpect(status().isOk());
        mvc.perform(as(manager, get("/stock/items/" + cf.grn.glass)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(cf.locationCode)))
                .andExpect(content().string(containsString("Movements here")))
                .andExpect(content().string(containsString("GRN-KGL-")));
        mvc.perform(as(manager, get("/stock/movements").param("item", cf.grn.glass.toString()).param("direction", "IN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GRN-KGL-")))
                .andExpect(content().string(containsString("10.000 SHEET")));

        mvc.perform(as(salesperson, get("/stock"))).andExpect(status().isForbidden());
        mvc.perform(as(salesperson, get("/stock/movements"))).andExpect(status().isForbidden());
    }

    @Test
    void aPlaceBeingCountedShowsAsCountedWithNoQuantity() throws Exception {
        cf.open(CountScope.PARTIAL, cf.grn.glass);
        SecurityContextHolder.clearContext();

        mvc.perform(as(manager, get("/stock/items/" + cf.grn.glass)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("under way here")))
                .andExpect(content().string(containsString("Being counted under")))
                .andExpect(content().string(not(containsString("80,000.00"))))
                .andExpect(content().string(not(containsString("10.000 SHEET"))));
        mvc.perform(as(manager, get("/stock/movements").param("item", cf.grn.glass.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("GRN-KGL-"))));
        mvc.perform(as(manager, get("/stock").param("location", cf.location.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1 place being counted, not included")));
    }
}
