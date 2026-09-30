package heritier.ntaganira.highbytes.wms.support;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.support
 * - File       : GrnFlow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Walks a goods received note through its lifecycle with the people its chain names
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.receiving.GrnForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.GrnLineForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A cast for receiving tests: the chain that binds today (whichever it is),
 * one user per step holding exactly that step's role, a Finance poster, a
 * supplier, a glass item received by the box and another received by the
 * piece.
 *
 * <p>Step 1's user raises and submits the note, as they would; later steps
 * are other people. Every call goes through the service, so every control
 * the application applies is applied here too.
 */
public class GrnFlow {

    public final Fixtures fx;
    public final ReceivingService receiving;

    public final List<String> chainRoles;
    /** Index 0 is the raiser and the step 1 signer. */
    public final List<UUID> stepUsers = new ArrayList<>();
    public final UUID poster;

    public final UUID supplier;
    public final UUID location;
    public final UUID bin;
    public final UUID glass;
    public final UUID silicone;
    public final UUID box;
    public final UUID pieces;

    public GrnFlow(Fixtures fx, ReceivingService receiving) {
        this.fx = fx;
        this.receiving = receiving;
        this.chainRoles = fx.receiptChainRoles();
        for (int i = 0; i < chainRoles.size(); i++) {
            stepUsers.add(fx.user("step" + (i + 1), chainRoles.get(i)));
        }
        this.poster = fx.user("finance", "FINANCE");

        this.supplier = fx.supplier("flow");
        this.location = fx.location("KGL-MAIN");
        this.bin = fx.bin("KGL-MAIN", "B");
        this.glass = fx.item("glass", "GLASS", "SHEET", new BigDecimal("6.00"));
        this.silicone = fx.item("sil", "SILICONE", "PC", null);
        this.box = fx.uom("BOX");
        this.pieces = fx.uom("PC");
        fx.conversion(glass, "BOX", "12.5");
    }

    public UUID raiser() {
        return stepUsers.get(0);
    }

    /**
     * Two lines: 4 boxes of glass at 100,000 (50 sheets, in a bin, 6.00 mm) and
     * 20 pieces of silicone at 3,000; landed cost 50,000 in all.
     */
    public GrnForm standardForm() {
        GrnForm form = new GrnForm();
        form.setSupplierId(supplier);
        form.setLocationId(location);
        form.setSupplierInvoiceNo("INV-1");
        form.setFreightRwf(new BigDecimal("30000"));
        form.setDutyRwf(new BigDecimal("15000"));
        form.setClearingRwf(new BigDecimal("4000"));
        form.setDemurrageRwf(new BigDecimal("1000"));

        GrnLineForm one = new GrnLineForm();
        one.setItemId(glass);
        one.setUomId(box);
        one.setQuantity(new BigDecimal("4"));
        one.setUnitPrice(new BigDecimal("100000"));
        one.setStorageBinId(bin);
        one.setMeasuredThicknessMm(new BigDecimal("6.00"));
        one.setSupplierQuantityBase(new BigDecimal("50"));
        form.getLines().add(one);

        GrnLineForm two = new GrnLineForm();
        two.setItemId(silicone);
        two.setUomId(pieces);
        two.setQuantity(new BigDecimal("20"));
        two.setUnitPrice(new BigDecimal("3000"));
        form.getLines().add(two);
        return form;
    }

    /** Ten more pieces of silicone at 4,000, no landed cost, no bin. */
    public GrnForm siliconeOnlyForm() {
        GrnForm form = new GrnForm();
        form.setSupplierId(supplier);
        form.setLocationId(location);
        GrnLineForm line = new GrnLineForm();
        line.setItemId(silicone);
        line.setUomId(pieces);
        line.setQuantity(new BigDecimal("10"));
        line.setUnitPrice(new BigDecimal("4000"));
        form.getLines().add(line);
        return form;
    }

    public UUID draft(GrnForm form) {
        fx.actAs(raiser());
        return receiving.create(form);
    }

    public UUID draft() {
        return draft(standardForm());
    }

    public UUID pending(GrnForm form) {
        UUID id = draft(form);
        fx.actAs(raiser());
        receiving.submit(id);
        return id;
    }

    public UUID pending() {
        return pending(standardForm());
    }

    /** Steps 2 to n sign, each as their own user. */
    public void signRemainingSteps(UUID id) {
        for (int i = 1; i < stepUsers.size(); i++) {
            fx.actAs(stepUsers.get(i));
            receiving.sign(id, true, null);
        }
    }

    public UUID approved(GrnForm form) {
        UUID id = pending(form);
        signRemainingSteps(id);
        return id;
    }

    public UUID approved() {
        return approved(standardForm());
    }

    /** Finance posts; returns the ticket's serial. */
    public String post(UUID id) {
        fx.actAs(poster);
        return receiving.post(id);
    }
}
