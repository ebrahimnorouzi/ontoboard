package de.fizkarlsruhe.ise.ontoboard.sheet;

import java.util.ArrayList;
import java.util.List;

/**
 * Writing a cell into a delimited file so that the reader gets the same cell back.
 *
 * <p>This exists because it was got wrong. Saving a sheet quoted a cell containing a double
 * quote; importing a workbook wrote the cell out raw. Both files were called {@code .tsv} and
 * both were read by the same reader - ROBOT's, which is OpenCSV - so the second kind came back
 * wrong, and in the worst way available: a cell holding one unbalanced {@code "} swallows the
 * tab after it, then the newline, then keeps going until it finds another quote. Every column
 * after it shifts left and every row after it shifts up. In the workbook this was found in, one
 * such cell moved an identifier from row 393 onto row 392 and changed 20,795 cells in all, with
 * no error anywhere; two other tabs ran to the end of the file looking for the closing quote and
 * were dropped by {@link SheetBook#open} without a word.
 *
 * <p>So there is now one escaper, used by everything that writes a delimited file, and a test
 * that reads back what each writer produced. A second copy of this logic is the bug.
 *
 * <p>The rules are RFC 4180's, which is what OpenCSV implements: a cell that contains the
 * delimiter, a quote, or a line break is wrapped in quotes, and each quote inside it is doubled.
 * A cell that contains none of those is written as it stands, so ordinary files stay readable by
 * anything that splits on tabs.
 */
public final class Delimited {

    private Delimited() {
    }

    /**
     * One cell, quoted if it has to be.
     */
    public static String escape(String cell, char delimiter) {
        String value = cell == null ? "" : cell;
        boolean needs = value.indexOf(delimiter) >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\t') >= 0;
        return needs ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    /**
     * One row, with its trailing newline.
     */
    public static String line(List<String> row, char delimiter) {
        StringBuilder text = new StringBuilder();
        for (int at = 0; at < row.size(); at++) {
            if (at > 0) {
                text.append(delimiter);
            }
            text.append(escape(row.get(at), delimiter));
        }
        return text.append('\n').toString();
    }

    /**
     * Delimited text read back into rows and cells, by the rules {@link #escape} writes by.
     *
     * <p>For text that did not come from a file: what is on the clipboard when somebody copies
     * a block out of Excel, which is exactly this format - tabs between cells, newlines between
     * rows, and a cell holding either of those wrapped in quotes. Files go through ROBOT's
     * reader instead, and a test holds the two to the same answers.
     *
     * <p>A trailing newline ends the last row rather than starting an empty one, because Excel
     * puts one there and a phantom row of blanks pasted under forty real ones is forty-one rows
     * of which one is wrong.
     */
    public static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<List<String>>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        List<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int at = 0;
        while (at < text.length()) {
            char each = text.charAt(at);
            if (quoted) {
                if (each == '"') {
                    boolean doubled = at + 1 < text.length() && text.charAt(at + 1) == '"';
                    if (doubled) {
                        cell.append('"');
                        at++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(each);
                }
            } else if (each == '"' && cell.length() == 0) {
                quoted = true;
            } else if (each == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (each == '\n' || each == '\r') {
                if (each == '\r' && at + 1 < text.length() && text.charAt(at + 1) == '\n') {
                    at++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<String>();
            } else {
                cell.append(each);
            }
            at++;
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /**
     * A cell with its line breaks and tabs turned into spaces.
     *
     * <p>For the import path only. Excel allows a cell to hold several lines, and quoting would
     * preserve them faithfully - but a ROBOT template row is a term and a sheet row is a line,
     * which is the shape every other tool in the pipeline reads, including the ones that are not
     * this one and do split on tabs. Flattening is a decision about the data; escaping is not.
     */
    public static String flatten(String cell) {
        String value = cell == null ? "" : cell;
        return value.replace('\t', ' ').replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }
}
