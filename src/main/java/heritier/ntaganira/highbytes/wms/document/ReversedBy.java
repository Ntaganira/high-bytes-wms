package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : ReversedBy.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The live reversing document raised against a document, for that document's own page
 * </pre>
 */

import java.util.UUID;

/**
 * A reversing document raised against another and not cancelled or rejected
 * (V21 allows one at a time). Read by the spine rather than the reversal
 * module, so a receipt's page never depends on holding a reversal right: the
 * serial is shown to everyone who reads the receipt, the link only to those
 * who may open it.
 */
public record ReversedBy(UUID id, String serialNo, String status) {

    /** Whether the reversal has posted, so every movement of the original is undone. */
    public boolean posted() {
        return "POSTED".equals(status);
    }
}
