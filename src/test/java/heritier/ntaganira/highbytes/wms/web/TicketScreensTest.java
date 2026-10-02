package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : TicketScreensTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The ticket register and a ticket render for whoever holds ticket.view, and a ticket's address finds it
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

/** As Finance (ticket.view), the receipt's raiser (no ticket right) and a salesperson (none: 403). */
class TicketScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReceivingService receiving;

    UUID receipt;
    UUID ticket;
    String ticketSerial;
    String receiptSerial;
    AppUserDetails finance;
    AppUserDetails raiser;
    AppUserDetails salesperson;

    @BeforeEach
    void post() {
        GrnFlow flow = new GrnFlow(fx, receiving);
        receipt = flow.approved();
        ticketSerial = flow.post(receipt);
        ticket = jdbc.sql("SELECT document_id FROM transaction_ticket WHERE source_document_id = :id")
                .param("id", receipt).query(UUID.class).single();
        receiptSerial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", receipt)
                .query(String.class).single();
        finance = userDetails.reload(fx.user("tfin", "FINANCE"), fx.kigali()).orElseThrow();
        raiser = userDetails.reload(flow.raiser(), fx.kigali()).orElseThrow();
        salesperson = userDetails.reload(fx.user("sales", "SALES"), fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    @Test
    void theRegisterAndATicketRender() throws Exception {
        mvc.perform(as(finance, get("/tickets").param("q", ticketSerial)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ticketSerial)))
                .andExpect(content().string(containsString(receiptSerial)))
                .andExpect(content().string(containsString("Receipt")));
        mvc.perform(as(finance, get("/tickets").param("movement", "DELIVERY").param("direction", "OUT")
                        .param("from", "2026-01-01")))
                .andExpect(status().isOk());
        mvc.perform(as(finance, get("/tickets/" + ticket)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("What moved")))
                .andExpect(content().string(containsString("In the ledger")))
                .andExpect(content().string(containsString(receiptSerial)))
                .andExpect(content().string(not(containsString("Awaiting signature"))))
                .andExpect(content().string(containsString("510,000.00")));
        mvc.perform(as(finance, get("/tickets/" + UUID.randomUUID()))).andExpect(status().isNotFound());
    }

    @Test
    void aTicketsAddressOpensItForWhoeverReadsTicketsAndItsDocumentOtherwise() throws Exception {
        mvc.perform(as(finance, get("/documents/" + ticket)))
                .andExpect(redirectedUrl("/tickets/" + ticket));
        mvc.perform(as(raiser, get("/documents/" + ticket)))
                .andExpect(redirectedUrl("/receiving/" + receipt));
        mvc.perform(as(salesperson, get("/tickets"))).andExpect(status().isForbidden());
        mvc.perform(as(salesperson, get("/tickets/" + ticket))).andExpect(status().isForbidden());
    }
}
