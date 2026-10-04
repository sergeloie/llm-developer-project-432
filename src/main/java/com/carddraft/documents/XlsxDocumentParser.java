package com.carddraft.documents;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import com.carddraft.agents.StructuralUnit;

/**
 * XLSX: a specification of article to parameters.
 *
 * <p>Each row becomes a sentence — "BLD-800: power 800 W; bowl 1.5 l" — because a specification is
 * not prose and a search over the raw cells will not find it. Semantic search cannot match a
 * spreadsheet's structure to a question, and full-text search over "800" alone matches every
 * article in the file; the sentence is what makes the row findable by both.
 *
 * <p>The first row is the column headers, and reading it is mandatory rather than defensive. A
 * table without headers is useless — "800" beside "power, W" and "800" beside "price, roubles"
 * are different facts, and a parser that silently guessed would attach the wrong meaning to every
 * value in the sheet.
 *
 * <p>Rows are marked as table units so the chunker never splits one: a row whose parameters are
 * torn in half produces two chunks that are each meaningless.
 */
@Component
public class XlsxDocumentParser implements DocumentParser {

    private final TextNormaliser normaliser;

    public XlsxDocumentParser(TextNormaliser normaliser) {
        this.normaliser = normaliser;
    }

    @Override
    public List<StructuralUnit> parse(byte[] content) {
        DataFormatter formatter = new DataFormatter();
        List<StructuralUnit> units = new ArrayList<>();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new DocumentRejectedException("the workbook has no sheets", null);
            }
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                Row header = sheet.getRow(sheetIndex == 0 ? 0 : sheet.getFirstRowNum());
                if (header == null) {
                    throw new DocumentRejectedException(
                            "sheet '" + sheet.getSheetName() + "' is empty, so it has no column headers", null);
                }
                List<String> headers = cellValues(header, formatter);
                if (headers.isEmpty() || headers.stream().allMatch(String::isBlank)) {
                    throw new DocumentRejectedException(
                            "sheet '" + sheet.getSheetName() + "' has an empty first row, "
                                    + "so the columns cannot be named", null);
                }

                for (int rowIndex = header.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        continue;
                    }
                    List<String> values = cellValues(row, formatter);
                    String sentence = toSentence(headers, values);
                    if (sentence != null) {
                        units.add(new StructuralUnit(1, sheet.getSheetName(), sentence, true));
                    }
                }
            }
        } catch (IOException e) {
            throw new DocumentRejectedException("the workbook could not be read", e.getMessage());
        }

        if (units.isEmpty()) {
            throw new DocumentRejectedException(
                    "the workbook has column headers but no data rows", null);
        }
        return units;
    }

    private String toSentence(List<String> headers, List<String> values) {
        String article = values.isEmpty() ? "" : values.get(0).strip();
        if (article.isEmpty()) {
            return null;
        }
        StringBuilder sentence = new StringBuilder(article).append(':');
        for (int column = 1; column < headers.size() && column < values.size(); column++) {
            String value = values.get(column).strip();
            if (value.isEmpty()) {
                continue;
            }
            sentence.append(' ').append(headers.get(column).strip().toLowerCase())
                    .append(' ').append(value).append(';');
        }
        String text = normaliser.normalise(sentence.toString());
        return text.endsWith(";") ? text.substring(0, text.length() - 1) : text;
    }

    private List<String> cellValues(Row row, DataFormatter formatter) {
        List<String> values = new ArrayList<>();
        for (int column = 0; column < row.getLastCellNum(); column++) {
            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            values.add(cell == null ? "" : formatter.formatCellValue(cell).strip());
        }
        return values;
    }

    @Override
    public boolean supports(String filename) {
        return filename != null && (filename.toLowerCase().endsWith(".xlsx")
                || filename.toLowerCase().endsWith(".xls"));
    }
}
