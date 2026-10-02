package heritier.ntaganira.highbytes.wms.support;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.support
 * - File       : CuttingFlow.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A cast for cutting tests: a place of its own stocked with sheets, the chain's signers, a poster, the gate
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.cutting.CuttingForm;
import heritier.ntaganira.highbytes.wms.inventory.cutting.CuttingOutputForm;
import heritier.ntaganira.highbytes.wms.inventory.cutting.CuttingService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DeliveryNoteService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DnForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.GrnForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.GrnLineForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A place of the test's own ({@link Fixtures#newLocation}), so a count elsewhere never freezes it, stocked through a
 * real receipt with 5 sheets of 3000 x 2000 mm, 6 mm glass at 100,000 each. One user per step of the cutting chain
 * (Finance prepares, the Warehouse Manager verifies, the Internal Controller releases), a second Finance officer who
 * posts, and a Warehouse Manager at the gate. Every call goes through the services, so every control applies.
 */
public class CuttingFlow {

    public final Fixtures fx;
    public final CuttingService cutting;
    public final DeliveryNoteService notes;
    public final GrnFlow grn;

    public final List<String> chainRoles;
    /** Index 0 prepares and raises; then the verifier and the releaser. */
    public final List<UUID> stepUsers = new ArrayList<>();
    public final UUID poster;
    public final UUID gate;
    public final UUID customer;

    public final String locationCode;
    public final UUID location;
    public final UUID bin;
    public final UUID sheet;
    public final String sheetCode;

    public CuttingFlow(Fixtures fx, CuttingService cutting, DeliveryNoteService notes, ReceivingService receiving) {
        this.fx = fx;
        this.cutting = cutting;
        this.notes = notes;
        this.grn = new GrnFlow(fx, receiving);
        this.chainRoles = fx.chainRoles("CUT");
        for (int i = 0; i < chainRoles.size(); i++) {
            stepUsers.add(fx.user("cut" + (i + 1), chainRoles.get(i)));
        }
        this.poster = fx.user("cutfin", "FINANCE");
        this.gate = fx.user("cutgate", "WH_MANAGER");
        this.customer = fx.customer("cut", false);
        this.locationCode = fx.newLocation("KGL");
        this.location = fx.location(locationCode);
        this.bin = fx.bin(locationCode, "S");
        this.sheet = fx.item("sheet", "GLASS", "SHEET", new BigDecimal("6.00"));
        fx.jdbc().sql("UPDATE item SET width_mm = 3000, height_mm = 2000 WHERE id = :id").param("id", sheet).update();
        this.sheetCode = fx.jdbc().sql("SELECT item_code FROM item WHERE id = :id").param("id", sheet)
                .query(String.class).single();
        stock("5");
    }

    public UUID raiser()   { return stepUsers.get(0); }
    public UUID verifier() { return stepUsers.get(1); }
    public UUID releaser() { return stepUsers.get(stepUsers.size() - 1); }

    /** Receives sheets into the bin at 100,000 each. */
    public void stock(String sheets) {
        GrnForm form = new GrnForm();
        form.setSupplierId(grn.supplier);
        form.setLocationId(location);
        GrnLineForm line = new GrnLineForm();
        line.setItemId(sheet);
        line.setUomId(fx.uom("SHEET"));
        line.setQuantity(new BigDecimal(sheets));
        line.setUnitPrice(new BigDecimal("100000"));
        line.setMeasuredThicknessMm(new BigDecimal("6.00"));
        line.setStorageBinId(bin);
        form.getLines().add(line);
        grn.post(grn.approved(form));
    }

    public static CuttingOutputForm output(String kind, String width, String height, String quantity) {
        CuttingOutputForm o = new CuttingOutputForm();
        o.setKind(kind);
        o.setWidthMm(new BigDecimal(width));
        o.setHeightMm(new BigDecimal(height));
        o.setQuantity(new BigDecimal(quantity));
        return o;
    }

    /** Two sheets (12 m²): six 1200 x 800 pieces (5.76 m²) and one 1800 x 600 off-cut kept (1.08 m²). */
    public CuttingForm standardForm() {
        CuttingForm form = new CuttingForm();
        form.setCustomerId(customer);
        form.setLocationId(location);
        form.setSheetItemId(sheet);
        form.setSheetBinId(bin);
        form.setSheets(new BigDecimal("2"));
        form.getOutputs().add(output("PIECE", "1200", "800", "6"));
        form.getOutputs().add(output("OFFCUT", "1800", "600", "1"));
        return form;
    }

    public UUID draft(CuttingForm form) {
        fx.actAs(raiser());
        return cutting.create(form);
    }

    public UUID pending(CuttingForm form) {
        UUID id = draft(form);
        fx.actAs(raiser());
        cutting.submit(id);
        return id;
    }

    /** Steps 2 to n sign, each as their own user. */
    public void signRemainingSteps(UUID id) {
        for (int i = 1; i < stepUsers.size(); i++) {
            fx.actAs(stepUsers.get(i));
            cutting.sign(id, true, null);
        }
    }

    public UUID released(CuttingForm form) {
        UUID id = pending(form);
        signRemainingSteps(id);
        return id;
    }

    public UUID posted(CuttingForm form) {
        UUID id = released(form);
        post(id);
        return id;
    }

    public String post(UUID id) {
        fx.actAs(poster);
        return cutting.post(id);
    }

    /** A note for the order's pieces, prefilled, with the gate's measurement. */
    public DnForm loadForm(UUID order) {
        fx.actAs(gate);
        DnForm form = notes.prefill(order);
        form.setVehicleRegistration("RAC 456 B");
        form.setDriverName("Cut Driver");
        form.getLines().forEach(l -> l.setMeasuredThicknessMm(new BigDecimal("6.00")));
        return form;
    }

    public UUID raiseNote(UUID order) {
        DnForm form = loadForm(order);
        fx.actAs(gate);
        return notes.create(form);
    }

    public String postNote(UUID note) {
        fx.actAs(gate);
        return notes.post(note);
    }

    /** On hand of an item at the flow's place, summed over bins. */
    public BigDecimal onHand(UUID item) {
        return fx.jdbc().sql("""
                SELECT COALESCE(SUM(qty_on_hand), 0) FROM stock_balance WHERE item_id = :item AND location_id = :loc
                """)
                .param("item", item).param("loc", location)
                .query(BigDecimal.class).single();
    }

    public UUID itemOf(String code) {
        return fx.jdbc().sql("SELECT id FROM item WHERE item_code = :code").param("code", code)
                .query(UUID.class).single();
    }
}
