package heritier.ntaganira.highbytes.wms.reporting.report;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.report
 * - File       : ReportTable.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A report as one table: its title, columns, rows, totals and notes, drawn on screen and exported
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One report, the same on the screen, in the PDF and in the workbook: every export is drawn from this, so the three
 * never disagree. Cells are strings, numbers ({@link java.math.BigDecimal}), dates and date-times, or null for
 * nothing to show.
 *
 * @param totals one cell per column (null where there is no total), or null for no totals row
 * @param notes  what a reader must know to read it right: what was left out and why
 */
public record ReportTable(String title,
                          String subtitle,
                          List<Column> columns,
                          List<List<Object>> rows,
                          List<Object> totals,
                          List<String> notes,
                          LocalDateTime generatedAt,
                          String generatedBy) {

    /** How a column's cells are written: text, a code read character by character, a quantity, money, a date. */
    public enum Kind { TEXT, CODE, QUANTITY, MONEY, PERCENT, DATE, DATETIME }

    public record Column(String label, Kind kind) {

        public boolean numeric() {
            return kind == Kind.QUANTITY || kind == Kind.MONEY || kind == Kind.PERCENT;
        }
    }

    public static Column text(String label)     { return new Column(label, Kind.TEXT); }
    public static Column code(String label)     { return new Column(label, Kind.CODE); }
    public static Column quantity(String label) { return new Column(label, Kind.QUANTITY); }
    public static Column money(String label)    { return new Column(label, Kind.MONEY); }
    public static Column percent(String label)  { return new Column(label, Kind.PERCENT); }
    public static Column date(String label)     { return new Column(label, Kind.DATE); }
    public static Column dateTime(String label) { return new Column(label, Kind.DATETIME); }

    /** Row {@code row}, column {@code column}, as the screen writes it. */
    public String cell(List<Object> row, int column) {
        return ReportExporter.text(column < row.size() ? row.get(column) : null, columns.get(column));
    }

    /** The totals row's cell, as the screen writes it. */
    public String total(int column) {
        return totals == null ? "" : cell(totals, column);
    }

    /** The period line for a dated report. */
    static String period(LocalDate from, LocalDate to) {
        var f = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy");
        return from.format(f) + " to " + to.format(f);
    }
}
