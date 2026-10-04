package heritier.ntaganira.highbytes.wms.reporting.report;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.report
 * - File       : ReportExporter.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A report table written as a PDF (OpenPDF) or an Excel workbook (Apache POI)
 * </pre>
 */

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Writes a {@link ReportTable}: a landscape A4 PDF with the title, the period, who generated it and when, the table
 * and its notes; or an .xlsx workbook with the same, numbers as numbers and dates as dates, so Finance can work
 * with them. Both are drawn from the same table the screen shows.
 */
@Component
public class ReportExporter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    // ---- PDF ---------------------------------------------------------------------------

    public byte[] pdf(ReportTable table) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.open();

        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font cell = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font mono = FontFactory.getFont(FontFactory.COURIER, 8);

        doc.add(new Paragraph("HIGH BYTES WMS · " + table.title(), title));
        doc.add(new Paragraph(table.subtitle(), small));
        doc.add(new Paragraph("Generated " + table.generatedAt().format(STAMP) + " (Kigali)"
                + (table.generatedBy() == null ? "" : " by " + table.generatedBy()), small));
        doc.add(new Paragraph(" ", small));

        List<ReportTable.Column> columns = table.columns();
        PdfPTable grid = new PdfPTable(columns.size());
        grid.setWidthPercentage(100);
        grid.setHeaderRows(1);
        for (ReportTable.Column c : columns) {
            PdfPCell h = new PdfPCell(new Phrase(c.label(), head));
            h.setHorizontalAlignment(c.numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            h.setGrayFill(0.9f);
            grid.addCell(h);
        }
        if (table.rows().isEmpty()) {
            PdfPCell none = new PdfPCell(new Phrase("Nothing to report for this period.", cell));
            none.setColspan(columns.size());
            grid.addCell(none);
        }
        for (List<Object> row : table.rows()) {
            addRow(grid, columns, row, cell, mono, false);
        }
        if (table.totals() != null) {
            addRow(grid, columns, table.totals(), head, head, true);
        }
        doc.add(grid);

        for (String note : table.notes()) {
            doc.add(new Paragraph(note, small));
        }
        doc.close();
        return out.toByteArray();
    }

    private static void addRow(PdfPTable grid, List<ReportTable.Column> columns, List<Object> row, Font font,
                               Font mono, boolean total) {
        for (int i = 0; i < columns.size(); i++) {
            ReportTable.Column c = columns.get(i);
            Object value = i < row.size() ? row.get(i) : null;
            PdfPCell cell = new PdfPCell(new Phrase(text(value, c), c.kind() == ReportTable.Kind.CODE && !total ? mono : font));
            cell.setHorizontalAlignment(c.numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            if (total) cell.setGrayFill(0.95f);
            grid.addCell(cell);
        }
    }

    /** A cell as the screen writes it: thousands separated, money to the franc's hundredth, dates in Kigali. */
    public static String text(Object value, ReportTable.Column column) {
        if (value == null) return "";
        if (value instanceof BigDecimal n) {
            return switch (column.kind()) {
                case MONEY -> decimal("#,##0.00").format(n.setScale(2, RoundingMode.HALF_UP));
                case PERCENT -> decimal("#,##0.0").format(n) + "%";
                default -> decimal("#,##0.###").format(n);
            };
        }
        if (value instanceof LocalDateTime t) return t.format(STAMP);
        if (value instanceof LocalDate d) return d.format(DAY);
        return value.toString();
    }

    private static DecimalFormat decimal(String pattern) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ENGLISH));
    }

    // ---- Excel -------------------------------------------------------------------------

    public byte[] xlsx(ReportTable table) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(sheetName(table.title()));
            CreationHelper help = book.getCreationHelper();

            XSSFFont bold = book.createFont();
            bold.setBold(true);
            CellStyle titleStyle = book.createCellStyle();
            XSSFFont big = book.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            titleStyle.setFont(big);
            CellStyle headStyle = book.createCellStyle();
            headStyle.setFont(bold);
            CellStyle money = book.createCellStyle();
            money.setDataFormat(help.createDataFormat().getFormat("#,##0.00"));
            CellStyle quantity = book.createCellStyle();
            quantity.setDataFormat(help.createDataFormat().getFormat("#,##0.###"));
            CellStyle date = book.createCellStyle();
            date.setDataFormat(help.createDataFormat().getFormat("dd mmm yyyy"));
            CellStyle stamp = book.createCellStyle();
            stamp.setDataFormat(help.createDataFormat().getFormat("dd mmm yyyy hh:mm"));
            CellStyle totalMoney = book.createCellStyle();
            totalMoney.cloneStyleFrom(money);
            totalMoney.setFont(bold);

            int r = 0;
            Row t = sheet.createRow(r++);
            t.createCell(0).setCellValue("HIGH BYTES WMS · " + table.title());
            t.getCell(0).setCellStyle(titleStyle);
            sheet.createRow(r++).createCell(0).setCellValue(table.subtitle());
            sheet.createRow(r++).createCell(0).setCellValue("Generated " + table.generatedAt().format(STAMP)
                    + " (Kigali)" + (table.generatedBy() == null ? "" : " by " + table.generatedBy()));
            r++;

            List<ReportTable.Column> columns = table.columns();
            Row header = sheet.createRow(r++);
            for (int i = 0; i < columns.size(); i++) {
                Cell c = header.createCell(i);
                c.setCellValue(columns.get(i).label());
                c.setCellStyle(headStyle);
            }
            for (List<Object> row : table.rows()) {
                Row x = sheet.createRow(r++);
                for (int i = 0; i < columns.size(); i++) {
                    write(x.createCell(i), i < row.size() ? row.get(i) : null, columns.get(i), money, quantity, date, stamp);
                }
            }
            if (table.totals() != null) {
                Row x = sheet.createRow(r++);
                for (int i = 0; i < columns.size(); i++) {
                    Object v = table.totals().get(i);
                    Cell c = x.createCell(i);
                    if (v instanceof BigDecimal n) {
                        c.setCellValue(n.doubleValue());
                        c.setCellStyle(totalMoney);
                    } else if (v != null) {
                        c.setCellValue(v.toString());
                        c.setCellStyle(headStyle);
                    }
                }
            }
            r++;
            for (String note : table.notes()) {
                sheet.createRow(r++).createCell(0).setCellValue(note);
            }
            for (int i = 0; i < columns.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Cell cell, Object value, ReportTable.Column column, CellStyle money, CellStyle quantity,
                              CellStyle date, CellStyle stamp) {
        if (value == null) return;
        if (value instanceof BigDecimal n) {
            cell.setCellValue(n.doubleValue());
            cell.setCellStyle(column.kind() == ReportTable.Kind.MONEY ? money : quantity);
        } else if (value instanceof LocalDateTime t) {
            cell.setCellValue(t);
            cell.setCellStyle(stamp);
        } else if (value instanceof LocalDate d) {
            cell.setCellValue(d);
            cell.setCellStyle(date);
        } else {
            cell.setCellValue(value.toString());
        }
    }

    /** An Excel sheet name: at most 31 characters, none of []:*?/\. */
    private static String sheetName(String title) {
        String clean = title.replaceAll("[\\[\\]:*?/\\\\]", " ");
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }
}
