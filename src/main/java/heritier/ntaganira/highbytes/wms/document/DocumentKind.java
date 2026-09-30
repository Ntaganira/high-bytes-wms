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

    GRN("GRN", "receiving", "Goods Received Note"),
    TT("TT", "ticket", "Transaction Ticket");

    private final String code;
    private final String module;
    private final String title;

    DocumentKind(String code, String module, String title) {
        this.code = code;
        this.module = module;
        this.title = title;
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
