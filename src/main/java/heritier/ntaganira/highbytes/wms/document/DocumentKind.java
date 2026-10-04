package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : DocumentKind.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The document types the engine serves, and the rights module each one signs under
 * </pre>
 */

/**
 * A document type as the engine knows it: its {@code document_type.code}, and
 * the permission module whose rights govern who may sign, cancel and post it.
 *
 * <p>The chain itself is never here: it is read from the document's bound
 * {@code workflow_definition}. What is here is only the mapping from a step's
 * action to the right that action needs, so a Warehouse Manager who holds the
 * step's role but not {@code receiving.verify} still cannot sign a VERIFY step.
 * A new document type adds one line.
 */
public enum DocumentKind {

    /**
     * The cutover: raised by the Warehouse Manager who counted the floor,
     * posted by Finance. One per location, ever, and only before anything
     * else has moved stock at the branch (V19).
     */
    OPB("OPB", "opening", "Opening Stock Balance"),
    GRN("GRN", "receiving", "Goods Received Note"),
    DAO("DAO", "dispatch", "Delivery Authorization Order"),
    /** Posted by the warehouse at the gate, so cancelling one is the gate's right too. */
    DN("DN", "dispatch", "Delivery Note", "post", "post"),
    /** Raised at the source branch; posting it is the dispatch at the source gate. */
    TRF("TRF", "transfer", "Inter-Warehouse Transfer", "create", "dispatch"),
    /** Raised at the destination branch; posting it is the receipt, and cancelling one is the receiver's right. */
    TRR("TRR", "transfer", "Transfer Receipt", "receive", "receive"),
    /** Raised by the warehouse manager; Finance posts it, so cancelling a draft is the raiser's right (damage.create). */
    DMG("DMG", "damage", "Return & Damage Report"),
    /** Opened by the warehouse manager; a second Finance officer posts its adjustment (count.post). */
    CNT("CNT", "count", "Physical Stock Count"),
    /** Prepared by Finance, released by the Internal Controller; a second Finance officer posts it (cutting.post). */
    CUT("CUT", "cutting", "Retail Cutting Order"),
    TT("TT", "ticket", "Transaction Ticket");

    private final String code;
    private final String module;
    private final String title;
    private final String cancelAction;
    private final String postAction;

    DocumentKind(String code, String module, String title) {
        this(code, module, title, "create", "post");
    }

    DocumentKind(String code, String module, String title, String cancelAction, String postAction) {
        this.postAction = postAction;
        this.code = code;
        this.module = module;
        this.title = title;
        this.cancelAction = cancelAction;
    }

    /** The right that cancels a document of this kind: whoever raises it, unless the type says otherwise. */
    public String cancelRight() {
        return right(cancelAction);
    }

    /** The right that posts a document of this kind: {@code receiving.post}, {@code transfer.dispatch}, {@code transfer.receive}. */
    public String postRight() {
        return right(postAction);
    }

    public String code()   { return code; }
    public String module() { return module; }
    public String title()  { return title; }

    /** A right of this kind's module: {@code right("post")} is {@code receiving.post}. */
    public String right(String action) {
        return module + "." + action;
    }

    /** The right that signing a step with this action label needs. */
    public String rightForStep(String actionLabel) {
        return right(switch (actionLabel) {
            case "PREPARE"     -> "create";
            case "VERIFY"      -> "verify";
            case "COUNTERSIGN" -> "countersign";
            case "APPROVE"     -> "approve";
            case "RELEASE"     -> "release";
            case "POST"        -> "post";
            default -> throw new IllegalArgumentException("Unknown step action " + actionLabel);
        });
    }

    public static DocumentKind of(String code) {
        for (DocumentKind kind : values()) {
            if (kind.code.equals(code)) return kind;
        }
        throw new IllegalArgumentException("The document engine does not serve type " + code + " yet.");
    }
}
