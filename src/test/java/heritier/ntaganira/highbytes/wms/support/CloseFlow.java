package heritier.ntaganira.highbytes.wms.support;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.support
 * - File       : CloseFlow.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A branch of its own with past days of stock, for testing the daily close
 * </pre>
 */

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A branch no other test uses, so the days it closes and locks touch nothing else: one warehouse, a sheet of glass,
 * and a Finance officer, an Internal Controller, a Warehouse Manager and a salesperson holding their roles there.
 *
 * <p>The ledger dates every movement the day it is recorded (V11), so a past day holds stock in a test only when
 * that rule is set aside. {@link #stockOn} writes a posted receipt ticket and its movement with the ledger's
 * triggers off for that one transaction ({@code session_replication_role}): the only path here that does not walk
 * the application's own, and the reason the balance cache never hears of it (the close reports that, as CACHE).
 */
public class CloseFlow {

    public final Fixtures fx;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public final String branchCode;
    public final UUID branch;
    public final String locationCode;
    public final UUID location;
    public final UUID glass;

    public final UUID finance;
    public final UUID controller;
    public final UUID manager;
    public final UUID salesperson;

    public CloseFlow(Fixtures fx, JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.fx = fx;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.branchCode = ("C" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
        jdbc.sql("INSERT INTO branch (code, name, branch_type) VALUES (:code, :name, 'BRANCH')")
                .param("code", branchCode)
                .param("name", "Close test branch " + branchCode)
                .update();
        this.branch = fx.branch(branchCode);
        this.locationCode = fx.newLocation(branchCode);
        this.location = fx.location(locationCode);
        this.glass = fx.item("closeglass", "GLASS", "SHEET", new BigDecimal("6.00"));
        this.finance = fx.userAt(branchCode, "clfin", "FINANCE");
        this.controller = fx.userAt(branchCode, "clic", "INTERNAL_CTRL");
        this.manager = fx.userAt(branchCode, "clwh", "WH_MANAGER");
        this.salesperson = fx.userAt(branchCode, "clsales", "SALES");
    }

    public LocalDate today() {
        return jdbc.sql("SELECT kigali_today()").query(LocalDate.class).single();
    }

    /** {@code sheets} of glass received at the branch's warehouse on {@code day}, at 1,000 a sheet, posted by Finance. */
    public void stockOn(LocalDate day, int sheets) {
        UUID ticket = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("""
                    INSERT INTO document (id, document_type_id, branch_id, serial_no, status, document_date,
                                          created_by, posted_at, posted_by)
                    SELECT :id, dt.id, :branch, :serial, 'POSTED', :day, :who, now(), :who
                      FROM document_type dt WHERE dt.code = 'TT'
                    """)
                    .param("id", ticket, Types.OTHER)
                    .param("branch", branch, Types.OTHER)
                    .param("serial", "TT-" + branchCode + "-" + ticket.toString().substring(0, 8))
                    .param("day", day)
                    .param("who", finance, Types.OTHER)
                    .update();
            jdbc.sql("""
                    INSERT INTO transaction_ticket (document_id, movement_type, direction, to_location_id)
                    VALUES (:id, 'RECEIPT', 'IN', :location)
                    """)
                    .param("id", ticket, Types.OTHER)
                    .param("location", location, Types.OTHER)
                    .update();
            jdbc.sql("""
                    INSERT INTO ticket_line (id, ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom)
                    SELECT :line, :id, 1, i.id, :qty, i.base_uom_id, :qty FROM item i WHERE i.id = :item
                    """)
                    .param("line", line, Types.OTHER)
                    .param("id", ticket, Types.OTHER)
                    .param("item", glass, Types.OTHER)
                    .param("qty", sheets)
                    .update();
            jdbc.sql("""
                    INSERT INTO stock_movement (ticket_line_id, document_id, branch_id, item_id, location_id, direction,
                                                quantity_base_uom, signed_quantity, unit_cost, value, running_balance,
                                                business_date, posted_by)
                    VALUES (:line, :id, :branch, :item, :location, 'IN', :qty, :qty, 1000, :value, :qty, :day, :who)
                    """)
                    .param("line", line, Types.OTHER)
                    .param("id", ticket, Types.OTHER)
                    .param("branch", branch, Types.OTHER)
                    .param("item", glass, Types.OTHER)
                    .param("location", location, Types.OTHER)
                    .param("qty", sheets)
                    .param("value", sheets * 1000)
                    .param("day", day)
                    .param("who", finance, Types.OTHER)
                    .update();
        });
    }

    /**
     * Writes one more movement against the branch's first ticket line, dated {@code day}, with every ledger rule
     * in force: what the lock is there to refuse.
     */
    public void moveThroughTheLedger(LocalDate day) {
        jdbc.sql("""
                INSERT INTO stock_movement (ticket_line_id, document_id, branch_id, item_id, location_id, direction,
                                            quantity_base_uom, signed_quantity, unit_cost, value, running_balance,
                                            business_date, posted_by)
                SELECT tl.id, tl.ticket_id, :branch, tl.item_id, :location, 'IN', 1, 1, 1000, 1000, 1, :day, :who
                  FROM ticket_line tl JOIN document d ON d.id = tl.ticket_id
                 WHERE d.branch_id = :branch
                 ORDER BY d.serial_no LIMIT 1
                """)
                .param("branch", branch, Types.OTHER)
                .param("location", location, Types.OTHER)
                .param("day", day)
                .param("who", finance, Types.OTHER)
                .update();
    }
}
