package heritier.ntaganira.highbytes.wms.support;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.support
 * - File       : OpeningFlow.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A cast for opening balance tests: the cutover chain, a place nothing has traded at, and two items
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.opening.OpeningForm;
import heritier.ntaganira.highbytes.wms.inventory.opening.OpeningLineForm;
import heritier.ntaganira.highbytes.wms.inventory.opening.OpeningService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A cast for the cutover: one user per step of the OPB chain holding exactly
 * that step's role, a Finance poster, and a <strong>branch of its own</strong>
 * with two places.
 *
 * <p>The branch matters. An opening balance may not post once anything else
 * has moved stock at its branch (V19), and the shared fixtures trade at KGL
 * and RBV, so a test using those would be refused by the control rather than
 * by the thing it meant to test.
 *
 * <p>Step 1's user raises and submits the sheet, as they would; later steps
 * are other people. Every call goes through the service, so every control the
 * application applies is applied here too.
 */
public class OpeningFlow {

    public final Fixtures fx;
    public final OpeningService opening;

    public final List<String> chainRoles;
    /** Index 0 is the raiser and the step 1 signer. */
    public final List<UUID> stepUsers = new ArrayList<>();
    public final UUID poster;

    /** A branch nothing has traded at, and two places in it. */
    public final String branchCode;
    public final String storeACode;
    public final String storeBCode;
    public final UUID storeA;
    public final UUID storeB;
    public final UUID bin;

    public final UUID glass;
    public final UUID silicone;
    public final UUID box;
    public final UUID pieces;

    public OpeningFlow(Fixtures fx, OpeningService opening) {
        this.fx = fx;
        this.opening = opening;
        this.chainRoles = fx.chainRoles("OPB");

        this.branchCode = fx.newBranch("OPB");
        this.storeACode = fx.newLocation(branchCode);
        this.storeBCode = fx.newLocation(branchCode);
        this.storeA = fx.location(storeACode);
        this.storeB = fx.location(storeBCode);
        this.bin = fx.bin(storeACode, "B1");

        for (int i = 0; i < chainRoles.size(); i++) {
            stepUsers.add(fx.userAt(branchCode, "obstep" + (i + 1), chainRoles.get(i)));
        }
        this.poster = fx.userAt(branchCode, "obfinance", "FINANCE");

        this.glass = fx.item("obglass", "GLASS", "SHEET", new BigDecimal("6.00"));
        this.silicone = fx.item("obsil", "SILICONE", "PC", null);
        this.box = fx.uom("BOX");
        this.pieces = fx.uom("PC");
        fx.conversion(glass, "BOX", "12.5");
    }

    public UUID raiser() {
        return stepUsers.get(0);
    }

    /**
     * Two lines into store A: 4 boxes of glass (50 sheets at 40,000 each, in a
     * bin, 6.00 mm) and 20 pieces of silicone at 3,000. Opening value
     * 2,060,000.
     */
    public OpeningForm standardForm() {
        return formFor(storeA);
    }

    public OpeningForm formFor(UUID location) {
        OpeningForm form = new OpeningForm();
        form.setLocationId(location);
        form.setAsAtDate(LocalDate.now().minusDays(1));
        form.setSourceSystem("QuickBooks");
        form.setBasisNote("Stock valuation report run 03 Oct 2026, struck by the Warehouse Manager");

        OpeningLineForm one = new OpeningLineForm();
        one.setItemId(glass);
        one.setUomId(box);
        one.setQuantity(new BigDecimal("4"));
        one.setUnitCost(new BigDecimal("40000"));
        one.setMeasuredThicknessMm(new BigDecimal("6.00"));
        if (location.equals(storeA)) {
            one.setStorageBinId(bin);
        }
        form.getLines().add(one);

        OpeningLineForm two = new OpeningLineForm();
        two.setItemId(silicone);
        two.setUomId(pieces);
        two.setQuantity(new BigDecimal("20"));
        two.setUnitCost(new BigDecimal("3000"));
        form.getLines().add(two);
        return form;
    }

    public UUID draft(OpeningForm form) {
        fx.actAs(raiser(), fx.branch(branchCode));
        return opening.create(form);
    }

    public UUID draft() {
        return draft(standardForm());
    }

    public UUID pending(OpeningForm form) {
        UUID id = draft(form);
        fx.actAs(raiser(), fx.branch(branchCode));
        opening.submit(id);
        return id;
    }

    public UUID pending() {
        return pending(standardForm());
    }

    /** Steps 2 to n sign, each as their own user. */
    public void signRemainingSteps(UUID id) {
        for (int i = 1; i < stepUsers.size(); i++) {
            fx.actAs(stepUsers.get(i), fx.branch(branchCode));
            opening.sign(id, true, null);
        }
    }

    public UUID approved(OpeningForm form) {
        UUID id = pending(form);
        signRemainingSteps(id);
        return id;
    }

    public UUID approved() {
        return approved(standardForm());
    }

    /** Finance posts; returns the ticket's serial. */
    public String post(UUID id) {
        fx.actAs(poster, fx.branch(branchCode));
        return opening.post(id);
    }

    public UUID posted() {
        UUID id = approved();
        post(id);
        return id;
    }

    /**
     * Makes the branch trade: a receipt posted into {@code storeB}, through
     * the real receiving chain. After this no opening balance may post at the
     * branch, which is the control this exists to set up.
     */
    public void tradeAt(heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService receiving) {
        List<String> grnRoles = fx.chainRoles("GRN");
        List<UUID> grnUsers = new ArrayList<>();
        for (int i = 0; i < grnRoles.size(); i++) {
            grnUsers.add(fx.userAt(branchCode, "obgrn" + (i + 1), grnRoles.get(i)));
        }
        UUID financePoster = fx.userAt(branchCode, "obgrnfin", "FINANCE");
        UUID branchId = fx.branch(branchCode);

        var form = new heritier.ntaganira.highbytes.wms.inventory.receiving.GrnForm();
        form.setSupplierId(fx.supplier("obflow"));
        form.setLocationId(storeB);
        form.setSupplierInvoiceNo("OB-TRADE-1");
        var line = new heritier.ntaganira.highbytes.wms.inventory.receiving.GrnLineForm();
        line.setItemId(silicone);
        line.setUomId(pieces);
        line.setQuantity(new BigDecimal("5"));
        line.setUnitPrice(new BigDecimal("3000"));
        form.getLines().add(line);

        fx.actAs(grnUsers.get(0), branchId);
        UUID grn = receiving.create(form);
        fx.actAs(grnUsers.get(0), branchId);
        receiving.submit(grn);
        for (int i = 1; i < grnUsers.size(); i++) {
            fx.actAs(grnUsers.get(i), branchId);
            receiving.sign(grn, true, null);
        }
        fx.actAs(financePoster, branchId);
        receiving.post(grn);
    }
}
