package heritier.ntaganira.highbytes.wms.support;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.support
 * - File       : TransferFlow.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Walks a transfer from Gahanga to Rubavu through approval, dispatch and receipt
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.inventory.transfer.ReceiptForm;
import heritier.ntaganira.highbytes.wms.inventory.transfer.ReceiptService;
import heritier.ntaganira.highbytes.wms.inventory.transfer.TransferForm;
import heritier.ntaganira.highbytes.wms.inventory.transfer.TransferLineForm;
import heritier.ntaganira.highbytes.wms.inventory.transfer.TransferService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A cast for transfer tests, on stock a goods received note has really posted
 * at Gahanga (50 sheets of glass in one bin at 443,478.26 and 20 pieces of
 * silicone with no bin at 66,521.74).
 *
 * <p>The approval chain is whichever binds today, read from its definition:
 * one user per step holding exactly that step's role, the first of whom raises
 * and submits. The dispatcher is an Assistant Warehouse Manager at Gahanga who
 * signs nothing; the receiver an Assistant Warehouse Manager at Rubavu. The
 * destination is Rubavu's bonded store, so a customs reference is always on
 * the transfer.
 */
public class TransferFlow {

    public final Fixtures fx;
    public final TransferService transfers;
    public final ReceiptService receipts;
    public final GrnFlow grn;

    public final List<String> chainRoles;
    /** Index 0 raises and signs step 1. */
    public final List<UUID> stepUsers = new ArrayList<>();
    public final UUID dispatcher;
    public final UUID receiver;
    public final UUID rubavu;
    public final UUID destination;
    public final UUID destinationBin;

    public TransferFlow(Fixtures fx, ReceivingService receiving, TransferService transfers, ReceiptService receipts) {
        this.fx = fx;
        this.transfers = transfers;
        this.receipts = receipts;
        this.grn = new GrnFlow(fx, receiving);
        this.chainRoles = fx.chainRoles("TRF");
        for (int i = 0; i < chainRoles.size(); i++) {
            stepUsers.add(fx.user("trf" + (i + 1), chainRoles.get(i)));
        }
        this.dispatcher = fx.user("dispatcher", "ASST_WH_MANAGER");
        this.rubavu = fx.branch("RBV");
        this.receiver = fx.userAt("RBV", "receiver", "ASST_WH_MANAGER");
        this.destination = fx.location("RBV-BOND");
        this.destinationBin = fx.bin("RBV-BOND", "R");
        grn.post(grn.approved());     // the shelf at Gahanga
    }

    public UUID raiser() {
        return stepUsers.get(0);
    }

    /** 20 sheets of glass from the bin and 5 pieces of silicone, to Rubavu's bonded store. */
    public TransferForm standardForm() {
        TransferForm form = new TransferForm();
        form.setFromLocationId(grn.location);
        form.setToLocationId(destination);
        form.setCustomsReference("C-TRF-0001");
        form.setNote("Restock Rubavu");
        form.getLines().add(line(grn.glass, "SHEET", "20", grn.bin));
        form.getLines().add(line(grn.silicone, "PC", "5", null));
        return form;
    }

    public TransferLineForm line(UUID item, String uom, String quantity, UUID bin) {
        TransferLineForm line = new TransferLineForm();
        line.setItemId(item);
        line.setUomId(fx.uom(uom));
        line.setQuantity(new BigDecimal(quantity));
        line.setStorageBinId(bin);
        return line;
    }

    public UUID draft(TransferForm form) {
        fx.actAs(raiser());
        return transfers.create(form);
    }

    public UUID pending(TransferForm form) {
        UUID id = draft(form);
        fx.actAs(raiser());
        transfers.submit(id);
        return id;
    }

    public UUID pending() {
        return pending(standardForm());
    }

    /** Steps 2 to n sign, each as their own user. */
    public void signRemainingSteps(UUID id) {
        for (int i = 1; i < stepUsers.size(); i++) {
            fx.actAs(stepUsers.get(i));
            transfers.sign(id, true, null);
        }
    }

    public UUID approved(TransferForm form) {
        UUID id = pending(form);
        signRemainingSteps(id);
        return id;
    }

    public UUID approved() {
        return approved(standardForm());
    }

    /** The warehouse dispatches at the Gahanga gate; returns the out ticket's serial. */
    public String dispatch(UUID id) {
        fx.actAs(dispatcher);
        return transfers.dispatch(id);
    }

    public UUID dispatched() {
        UUID id = approved();
        dispatch(id);
        return id;
    }

    /** A receipt form prefilled at the dispatched quantities, glass put into the Rubavu bin. */
    public ReceiptForm receiptForm(UUID transfer) {
        fx.actAs(receiver, rubavu);
        ReceiptForm form = receipts.prefill(transfer);
        form.getLines().get(0).setStorageBinId(destinationBin);
        return form;
    }

    public UUID raiseReceipt(ReceiptForm form) {
        fx.actAs(receiver, rubavu);
        return receipts.create(form);
    }

    public String postReceipt(UUID receipt) {
        fx.actAs(receiver, rubavu);
        return receipts.post(receipt);
    }
}
