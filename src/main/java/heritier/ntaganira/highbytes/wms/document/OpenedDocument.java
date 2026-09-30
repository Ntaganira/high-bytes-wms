package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : OpenedDocument.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The id and serial of a document the engine has just opened
 * </pre>
 */

import java.util.UUID;

public record OpenedDocument(UUID id, String serialNo) {}
