package heritier.ntaganira.highbytes.wms.support;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.support
 * - File       : CountFlow.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A cast for count tests: the count chain's people, a second Finance poster, a store of its own
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.count.CountEntryForm;
import heritier.ntaganira.highbytes.wms.inventory.count.CountForm;
import heritier.ntaganira.highbytes.wms.inventory.count.CountLineRow;
import heritier.ntaganira.highbytes.wms.inventory.count.CountScope;
import heritier.ntaganira.highbytes.wms.inventory.count.CountService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.GrnForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.GrnLineForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The count chain is read from the definition in force (Warehouse Manager,
 * Internal Controller, Finance), one user per step holding exactly that step's
 * role: the first opens, counts and submits; the second takes the verification
 * count and signs it; the third approves. A second Finance officer, who signs
 * nothing, posts. Stock is received through real goods received notes into a
 * location no other test uses, because a count freezes what it counts there.
 * Everything goes through the services, so every control the application
 * applies is applied here too.
 */
public class CountFlow {

    public final Fixtures fx;
    public final CountService counts;
    public final GrnFlow grn;

    public final List<String> chainRoles;
    /** Index 0 opens, counts and signs step 1. */
    public final List<UUID> stepUsers = new ArrayList<>();
    public final UUID poster;

    public final String locationCode;
    public final UUID location;
    public final UUID bin;

    public CountFlow(Fixtures fx, CountService counts, ReceivingService receiving) {
        this.fx = fx;
        this.counts = counts;
        this.grn = new GrnFlow(fx, receiving);
        this.chainRoles = fx.chainRoles("CNT");
        for (int i = 0; i < chainRoles.size(); i++) {
            stepUsers.add(fx.user("cnt" + (i + 1), chainRoles.get(i)));
        }
        this.poster = fx.user("cntfin", "FINANCE");
        this.locationCode = fx.newLocation("KGL");
        this.location = fx.location(locationCode);
        this.bin = fx.bin(locationCode, "C");
    }

    public UUID raiser()   { return stepUsers.get(0); }
    public UUID verifier() { return stepUsers.get(chainRoles.indexOf("INTERNAL_CTRL")); }
    public UUID approver() { return stepUsers.get(stepUsers.size() - 1); }

    /** Receives sheets of glass at 8,000 (into the bin, or unbinned) and pieces of silicone at 3,000, unbinned. */
    public void stock(String glassSheets, boolean binned, String siliconePieces) {
        GrnForm form = new GrnForm();
        form.setSupplierId(grn.supplier);
        form.setLocationId(location);
        if (glassSheets != null) {
            GrnLineForm glass = new GrnLineForm();
            glass.setItemId(grn.glass);
            glass.setUomId(fx.uom("SHEET"));
            glass.setQuantity(new BigDecimal(glassSheets));
            glass.setUnitPrice(new BigDecimal("8000"));
            glass.setMeasuredThicknessMm(new BigDecimal("6.00"));
            glass.setStorageBinId(binned ? bin : null);
            form.getLines().add(glass);
        }
        if (siliconePieces != null) {
            GrnLineForm silicone = new GrnLineForm();
            silicone.setItemId(grn.silicone);
            silicone.setUomId(grn.pieces);
            silicone.setQuantity(new BigDecimal(siliconePieces));
            silicone.setUnitPrice(new BigDecimal("3000"));
            form.getLines().add(silicone);
        }
        grn.post(grn.approved(form));
    }

    public UUID open(CountScope scope, UUID... items) {
        CountForm form = new CountForm();
        form.setLocationId(location);
        form.setScope(scope);
        form.setItemIds(new ArrayList<>(List.of(items)));
        fx.actAs(raiser());
        return counts.create(form);
    }

    /** The raiser counts every line: {@code found} says what they find on it (null leaves it uncounted). */
    public void count(UUID id, Function<CountLineRow, String> found) {
        fx.actAs(raiser());
        var sheet = counts.countingSheet(id);
        for (int i = 0; i < sheet.lines().size(); i++) {
            String quantity = found.apply(sheet.lines().get(i));
            if (quantity != null) sheet.form().getLines().get(i).setQuantity(new BigDecimal(quantity));
        }
        counts.saveCounts(id, sheet.form());
    }

    public void submit(UUID id) {
        fx.actAs(raiser());
        counts.submit(id);
    }

    /** The verifier recounts every line chosen for it: {@code found} says what they find (null leaves it). */
    public void verify(UUID id, Function<CountLineRow, String> found) {
        fx.actAs(verifier());
        var sheet = counts.verificationSheet(id);
        for (int i = 0; i < sheet.lines().size(); i++) {
            CountLineRow line = sheet.lines().get(i);
            if (!line.verifyRequired()) continue;
            String quantity = found.apply(line);
            if (quantity != null) sheet.form().getLines().get(i).setQuantity(new BigDecimal(quantity));
        }
        counts.saveVerification(id, sheet.form());
    }

    /** Steps 2 to n sign, each as their own user. */
    public void signRemainingSteps(UUID id) {
        for (int i = 1; i < stepUsers.size(); i++) {
            fx.actAs(stepUsers.get(i));
            counts.sign(id, true, null);
        }
    }

    /** Counted by item code, submitted, recounted the same, signed by everyone: approved. */
    public UUID approved(Function<CountLineRow, String> found) {
        UUID id = open(CountScope.FULL);
        count(id, found);
        submit(id);
        verify(id, found);
        signRemainingSteps(id);
        return id;
    }

    public List<String> post(UUID id) {
        fx.actAs(poster);
        return counts.post(id);
    }

    /** A blank entry form row for a line, as a counter would leave it. */
    public static CountEntryForm.Line row(UUID lineId, String quantity) {
        CountEntryForm.Line line = new CountEntryForm.Line(lineId, null, null);
        line.setQuantity(quantity == null ? null : new BigDecimal(quantity));
        return line;
    }
}
