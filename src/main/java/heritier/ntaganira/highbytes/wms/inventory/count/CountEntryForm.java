package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : CountEntryForm.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The quantities a counter or the verifier enters against the lines of a sheet
 * </pre>
 */

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One form for the first count and for the verification count. Each line
 * carries the quantity the page was opened with ({@code was}); only a line whose
 * quantity or note the person changed is written, so two counters on one sheet,
 * each saving the lines they counted, never blank each other's work. A blank
 * quantity is no count: nothing is cleared by leaving a box empty.
 */
public class CountEntryForm {

    private List<Line> lines = new ArrayList<>();

    public List<Line> getLines() { return lines; }
    public void setLines(List<Line> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }

    public static class Line {
        private UUID lineId;
        private BigDecimal quantity;
        private BigDecimal was;
        private String note;
        private String noteWas;

        public Line() {}

        public Line(UUID lineId, BigDecimal quantity, String note) {
            this.lineId = lineId;
            this.quantity = quantity;
            this.was = quantity;
            this.note = note;
            this.noteWas = note;
        }

        /** Whether this line carries a change the page did not open with. */
        public boolean changed() {
            boolean quantityChanged = quantity != null && (was == null || quantity.compareTo(was) != 0);
            return quantityChanged || noteChanged();
        }

        public boolean noteChanged() {
            String now = note == null || note.isBlank() ? null : note.trim();
            String before = noteWas == null || noteWas.isBlank() ? null : noteWas.trim();
            return now == null ? before != null : !now.equals(before);
        }

        public UUID getLineId() { return lineId; }
        public void setLineId(UUID lineId) { this.lineId = lineId; }

        public BigDecimal getQuantity() { return quantity; }
        public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

        public BigDecimal getWas() { return was; }
        public void setWas(BigDecimal was) { this.was = was; }

        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }

        public String getNoteWas() { return noteWas; }
        public void setNoteWas(String noteWas) { this.noteWas = noteWas; }
    }
}
