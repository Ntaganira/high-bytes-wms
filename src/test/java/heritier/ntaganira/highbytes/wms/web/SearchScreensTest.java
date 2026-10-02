package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : SearchScreensTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The search box finds documents and master data only as far as the reader's rights reach
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.GrnFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** As a Warehouse Manager (documents and items), Finance (also suppliers), a salesperson and an administrator. */
class SearchScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReceivingService receiving;

    UUID pending;
    String serial;
    String itemCode;
    String supplierCode;
    AppUserDetails manager;
    AppUserDetails finance;
    AppUserDetails salesperson;

    @BeforeEach
    void cast() {
        GrnFlow flow = new GrnFlow(fx, receiving);
        pending = flow.pending();
        serial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", pending)
                .query(String.class).single();
        itemCode = jdbc.sql("SELECT item_code FROM item WHERE id = :id").param("id", flow.glass)
                .query(String.class).single();
        supplierCode = jdbc.sql("SELECT code FROM supplier WHERE id = :id").param("id", flow.supplier)
                .query(String.class).single();
        manager = userDetails.reload(fx.user("whm", "WH_MANAGER"), fx.kigali()).orElseThrow();
        finance = userDetails.reload(fx.user("fin", "FINANCE"), fx.kigali()).orElseThrow();
        salesperson = userDetails.reload(fx.user("sales", "SALES"), fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    @Test
    void aSerialTypedWholeOpensItsDocumentAndPartOfOneListsIt() throws Exception {
        mvc.perform(as(manager, get("/search").param("q", " " + serial.toLowerCase() + " ")))
                .andExpect(redirectedUrl("/documents/" + pending));
        String part = serial.substring(0, serial.length() - 1);
        mvc.perform(as(manager, get("/search").param("q", part)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(serial)))
                .andExpect(content().string(containsString("All matching documents")));
    }

    @Test
    void eachSectionIsSearchedOnlyForWhoeverMayReadIt() throws Exception {
        // The Warehouse Manager reads items, not suppliers.
        mvc.perform(as(manager, get("/search").param("q", itemCode)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/items/")))
                .andExpect(content().string(containsString(itemCode)));
        mvc.perform(as(manager, get("/search").param("q", supplierCode)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/suppliers/"))))
                .andExpect(content().string(containsString("Nothing found")));
        // Finance manages partners.
        mvc.perform(as(finance, get("/search").param("q", supplierCode)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/suppliers/")));
        // A salesperson reads neither receipts nor the item master.
        mvc.perform(as(salesperson, get("/search").param("q", serial.substring(0, serial.length() - 1))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/documents/" + pending))));
        mvc.perform(as(salesperson, get("/search").param("q", itemCode)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/items/"))));
        // An administrator may search; nothing operational answers.
        mvc.perform(as(userDetails.reload(fx.user("adm", "SYS_ADMIN"), fx.kigali()).orElseThrow(),
                        get("/search").param("q", itemCode)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing found")));
    }

    @Test
    void tooLittleOrAWildcardFindsNothing() throws Exception {
        mvc.perform(as(manager, get("/search").param("q", "G")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Type at least two characters")));
        mvc.perform(as(manager, get("/search")))
                .andExpect(status().isOk());
        // The reader's % and _ are characters, not wildcards.
        mvc.perform(as(manager, get("/search").param("q", "%%")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing found")));
        mvc.perform(as(manager, get("/search").param("q", "__")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing found")));
    }
}
