package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : ReceiptForm.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The transfer receipt create/edit form
 * </pre>
 */

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The form: which transfer, a note, and a row per transfer line. */
public class ReceiptForm {

    private UUID id;
    private Integer version;

    @NotNull(message = "A receipt is raised against a transfer")
    private UUID transferId;

    @Size(max = 400, message = "At most 400 characters")
    private String note;

    @Valid
    @Size(min = 1, message = "A receipt needs at least one line")
    private List<ReceiptLineForm> lines = new ArrayList<>();

    public boolean isNew() { return id == null; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public UUID getTransferId() { return transferId; }
    public void setTransferId(UUID transferId) { this.transferId = transferId; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note == null || note.isBlank() ? null : note.trim(); }

    public List<ReceiptLineForm> getLines() { return lines; }
    public void setLines(List<ReceiptLineForm> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }
}
