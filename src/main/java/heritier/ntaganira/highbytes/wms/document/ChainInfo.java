package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : ChainInfo.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Which approval chain a document is bound to, and why
 * </pre>
 */

import java.time.LocalDate;

/**
 * The chain a document is bound to. Shown beside the approval steps so a
 * reader sees that the chain is the one in force on the document's creation
 * date, and that the document finishes under it.
 */
public record ChainInfo(int version, String basis, LocalDate effectiveFrom, LocalDate effectiveTo, String notes) {

    public String getBasis() { return basis; }
    public int getVersion()  { return version; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo()   { return effectiveTo; }
    public String getNotes() { return notes; }
}
