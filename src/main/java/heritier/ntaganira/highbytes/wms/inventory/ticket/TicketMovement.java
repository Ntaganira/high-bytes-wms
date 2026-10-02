package heritier.ntaganira.highbytes.wms.inventory.ticket;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.ticket
 * - File       : TicketMovement.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The movement a transaction ticket records, as transaction_ticket.movement_type names it
 * </pre>
 */

/** {@code transaction_ticket.movement_type} (V4), with the words a person reads. */
public enum TicketMovement {

    RECEIPT("Receipt"),
    DELIVERY("Delivery"),
    TRANSFER_OUT("Transfer out"),
    TRANSFER_IN("Transfer in"),
    ISSUE_TO_VAN("Issue to van"),
    RETURN("Customer return"),
    DAMAGE("Damage or loss"),
    CUT_CONSUME("Cutting: sheet consumed"),
    CUT_OUTPUT("Cutting: pieces and off-cuts"),
    ADJUSTMENT("Count adjustment");

    private final String label;

    TicketMovement(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The movement a code names, or null for anything else (a filter left empty, or a value nobody offered). */
    public static TicketMovement of(String code) {
        if (code == null) return null;
        for (TicketMovement m : values()) {
            if (m.name().equals(code)) return m;
        }
        return null;
    }
}
