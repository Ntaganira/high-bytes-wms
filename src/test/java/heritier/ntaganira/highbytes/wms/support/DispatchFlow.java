package heritier.ntaganira.highbytes.wms.support;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.support
 * - File       : DispatchFlow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Walks a delivery authorization and its delivery note through the gate with the people the chain names
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.dispatch.DaoForm;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DaoLineForm;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DeliveryNoteService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DispatchService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DnForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A cast for dispatch tests, on stock a goods received note has really posted.
 *
 * <p>Stock: 50 sheets of glass in one bin (received by the box, value
 * 443,478.26 with landed cost) and 20 pieces of silicone with no bin. The
 * authorization chain is whichever binds today, read from its definition: one
 * user per step holding exactly that step's role, the first of whom raises and
 * submits. The gate is a warehouse user who signed nothing and raised nothing.
 */
public class DispatchFlow {

    public final Fixtures fx;
    public final DispatchService dispatch;
    public final DeliveryNoteService notes;
    public final GrnFlow grn;

    public final List<String> chainRoles;
    /** Index 0 raises and signs step 1. */
    public final List<UUID> stepUsers = new ArrayList<>();
    /** A Warehouse Manager who is not in the chain for this authorization and raised nothing. */
    public final UUID gate;
    public final UUID customer;
    public final UUID blockedCustomer;

    public DispatchFlow(Fixtures fx, ReceivingService receiving, DispatchService dispatch,
                        DeliveryNoteService notes) {
        this.fx = fx;
        this.dispatch = dispatch;
        this.notes = notes;
        this.grn = new GrnFlow(fx, receiving);
        this.chainRoles = fx.chainRoles("DAO");
        for (int i = 0; i < chainRoles.size(); i++) {
            stepUsers.add(fx.user("dao" + (i + 1), chainRoles.get(i)));
        }
        this.gate = fx.user("gate", "WH_MANAGER");
        this.customer = fx.customer("flow", false);
        this.blockedCustomer = fx.customer("blocked", true);
        // The shelf: the glass and the silicone the receipt posts.
        grn.post(grn.approved());
    }

    public UUID raiser() {
        return stepUsers.get(0);
    }

    /** 20 sheets of glass and 5 pieces of silicone for the customer. */
    public DaoForm standardForm() {
        DaoForm form = new DaoForm();
        form.setCustomerId(customer);
        form.setLocationId(grn.location);
        form.setCustomerReference("PO-77");
        form.getLines().add(line(grn.glass, "SHEET", "20"));
        form.getLines().add(line(grn.silicone, "PC", "5"));
        return form;
    }

    public DaoLineForm line(UUID item, String uom, String quantity) {
        DaoLineForm line = new DaoLineForm();
        line.setItemId(item);
        line.setUomId(fx.uom(uom));
        line.setQuantity(new BigDecimal(quantity));
        return line;
    }

    public UUID draft(DaoForm form) {
        fx.actAs(raiser());
        return dispatch.create(form);
    }

    public UUID pending(DaoForm form) {
        UUID id = draft(form);
        fx.actAs(raiser());
        dispatch.submit(id);
        return id;
    }

    public UUID pending() {
        return pending(standardForm());
    }

    /** Steps 2 to n sign, each as their own user; the last is the Internal Controller's release. */
    public void signRemainingSteps(UUID id) {
        for (int i = 1; i < stepUsers.size(); i++) {
            fx.actAs(stepUsers.get(i));
            dispatch.sign(id, true, null);
        }
    }

    /** Signed by the whole chain: released. */
    public UUID released(DaoForm form) {
        UUID id = pending(form);
        signRemainingSteps(id);
        return id;
    }

    public UUID released() {
        return released(standardForm());
    }

    /** A note prefilled at the authorized quantities, with the glass bin and gate measurements filled in. */
    public DnForm loadForm(UUID dao) {
        fx.actAs(gate);
        DnForm form = notes.prefill(dao);
        form.setVehicleRegistration("RAD 123 A");
        form.setDriverName("Jean Driver");
        form.setDriverPhone("0788000000");
        form.getLines().forEach(l -> {
            // Glass leaves the bin it was received into, and is measured at the gate.
            var authLine = notes.contextFor(dao).daoLines().stream()
                    .filter(a -> a.id().equals(l.getAuthorizationLineId())).findFirst().orElseThrow();
            if (authLine.glass()) {
                l.setStorageBinId(grn.bin);
                l.setMeasuredThicknessMm(new BigDecimal("6.00"));
            }
        });
        return form;
    }

    public UUID raiseNote(UUID dao) {
        DnForm form = loadForm(dao);
        fx.actAs(gate);
        return notes.create(form);
    }

    /** The warehouse posts at the gate; returns the ticket serial. */
    public String post(UUID note) {
        fx.actAs(gate);
        return notes.post(note);
    }
}
