package heritier.ntaganira.highbytes.wms.support;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.support
 * - File       : DamageFlow.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A cast for return and damage tests: the report chain that binds today, a Finance poster, forms and steps
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.damage.DamageForm;
import heritier.ntaganira.highbytes.wms.inventory.damage.DamageKind;
import heritier.ntaganira.highbytes.wms.inventory.damage.DamageLineForm;
import heritier.ntaganira.highbytes.wms.inventory.damage.DamageService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The report chain is read from the definition in force (Warehouse Manager,
 * Internal Controller, Managing Director on both sides of the 2027 switch), one
 * user per step holding exactly that step's role, the first of whom raises and
 * submits. Finance posts and signs nothing. Everything goes through the
 * service, so every control the application applies is applied here too.
 */
public class DamageFlow {

    public final Fixtures fx;
    public final DamageService reports;

    public final List<String> chainRoles;
    /** Index 0 raises and signs step 1. */
    public final List<UUID> stepUsers = new ArrayList<>();
    public final UUID poster;

    public DamageFlow(Fixtures fx, DamageService reports) {
        this.fx = fx;
        this.reports = reports;
        this.chainRoles = fx.chainRoles("DMG");
        for (int i = 0; i < chainRoles.size(); i++) {
            stepUsers.add(fx.user("dmg" + (i + 1), chainRoles.get(i)));
        }
        this.poster = fx.user("dmgfin", "FINANCE");
    }

    public UUID raiser() {
        return stepUsers.get(0);
    }

    /** A line naming an item: a write-off or a release. */
    public DamageLineForm itemLine(UUID item, String uom, String quantity, UUID fromBin, UUID toBin) {
        DamageLineForm line = new DamageLineForm();
        line.setItemId(item);
        line.setUomId(fx.uom(uom));
        line.setQuantity(new BigDecimal(quantity));
        line.setStorageBinId(fromBin);
        line.setToStorageBinId(toBin);
        return line;
    }

    /** A write-off at Gahanga's main store of this much of an item, with its bin if it is binned. */
    public DamageForm writeOff(UUID item, String uom, String quantity, UUID bin) {
        DamageForm form = new DamageForm();
        form.setKind(DamageKind.WRITE_OFF);
        form.setReasonCode("DAMAGED");
        form.setReason("Broken while being moved to the cutting floor");
        form.setFromLocationId(fx.location("KGL-MAIN"));
        form.getLines().add(itemLine(item, uom, quantity, bin, null));
        return form;
    }

    /** A release from Gahanga's quarantine into its main store. */
    public DamageForm release(UUID item, String uom, String quantity) {
        DamageForm form = new DamageForm();
        form.setKind(DamageKind.QUARANTINE_RELEASE);
        form.setReasonCode("INSPECTION_PASSED");
        form.setReason("Inspected by the quality clerk: no damage found");
        form.setFromLocationId(fx.location("KGL-QUAR"));
        form.setToLocationId(fx.location("KGL-MAIN"));
        form.getLines().add(itemLine(item, uom, quantity, null, null));
        return form;
    }

    public UUID draft(DamageForm form) {
        fx.actAs(raiser());
        return reports.create(form);
    }

    public UUID pending(DamageForm form) {
        UUID id = draft(form);
        fx.actAs(raiser());
        reports.submit(id);
        return id;
    }

    /** Steps 2 to n sign, each as their own user. */
    public void signRemainingSteps(UUID id) {
        for (int i = 1; i < stepUsers.size(); i++) {
            fx.actAs(stepUsers.get(i));
            reports.sign(id, true, null);
        }
    }

    public UUID approved(DamageForm form) {
        UUID id = pending(form);
        signRemainingSteps(id);
        return id;
    }

    /** Finance posts; returns the first ticket's serial. */
    public String post(UUID id) {
        fx.actAs(poster);
        return reports.post(id);
    }
}
