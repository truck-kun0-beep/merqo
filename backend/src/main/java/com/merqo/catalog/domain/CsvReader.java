package com.merqo.catalog.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * A small reader of comma separated values.
 *
 * <p>Responsibility: turn the text of a CSV file into rows of cells, following the rules of RFC
 * 4180.
 *
 * <p>The rules this reader follows:
 *
 * <ul>
 *   <li>A cell may be written between double quotes.
 *   <li>A quoted cell may contain commas, and it may contain a line break.
 *   <li>Two double quotes inside a quoted cell stand for one double quote.
 *   <li>The file may start with a UTF-8 byte order mark. It is dropped.
 *   <li>The lines may end with {@code \n} or with {@code \r\n}.
 *   <li>An empty line is skipped.
 * </ul>
 *
 * <p>No external CSV library is used. The reader is about a hundred lines of plain Java, it has no
 * Spring in it, and it is unit tested with the awkward cases: quotes, commas inside quotes, line
 * breaks inside quotes, a byte order mark and empty lines.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Single responsibility</b> - the reader only reads. It knows nothing about products.
 *   <li><b>Encapsulation</b> - the scanning state is private, so a caller cannot push the reader
 *       into a strange state.
 *   <li><b>Immutability</b> - the answer is an unmodifiable list of unmodifiable rows.
 * </ul>
 */
public final class CsvReader {

    /** The byte order mark of a UTF-8 file, as a character. */
    public static final char BYTE_ORDER_MARK = '﻿';

    private CsvReader() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Reads the rows of a CSV text.
     *
     * @param text the whole file
     * @return the rows, never {@code null}
     */
    public static List<List<String>> read(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String content = text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;

        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean cellWasQuoted = false;

        int index = 0;
        while (index < content.length()) {
            char character = content.charAt(index);

            if (inQuotes) {
                if (character == '"') {
                    boolean isEscapedQuote =
                            index + 1 < content.length() && content.charAt(index + 1) == '"';
                    if (isEscapedQuote) {
                        cell.append('"');
                        index += 2;
                        continue;
                    }
                    inQuotes = false;
                    index++;
                    continue;
                }
                cell.append(character);
                index++;
                continue;
            }

            if (character == '"' && cell.isEmpty()) {
                inQuotes = true;
                cellWasQuoted = true;
                index++;
                continue;
            }
            if (character == ',') {
                row.add(finish(cell, cellWasQuoted));
                cell.setLength(0);
                cellWasQuoted = false;
                index++;
                continue;
            }
            if (character == '\r' || character == '\n') {
                int next = index + 1;
                if (character == '\r' && next < content.length() && content.charAt(next) == '\n') {
                    next++;
                }
                row.add(finish(cell, cellWasQuoted));
                cell.setLength(0);
                cellWasQuoted = false;
                if (!isEmptyRow(row)) {
                    rows.add(List.copyOf(row));
                }
                row = new ArrayList<>();
                index = next;
                continue;
            }
            cell.append(character);
            index++;
        }

        // The file may end without a line break.
        if (cell.length() > 0 || cellWasQuoted || !row.isEmpty()) {
            row.add(finish(cell, cellWasQuoted));
            if (!isEmptyRow(row)) {
                rows.add(List.copyOf(row));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Finishes one cell.
     *
     * <p>A cell that was written between quotes keeps its spaces. A cell that was not quoted is
     * trimmed, because a space after a comma is usually a typo in the file and not part of the
     * value.
     *
     * @param cell the text of the cell
     * @param wasQuoted whether the cell was written between quotes
     * @return the value of the cell
     */
    static String finish(StringBuilder cell, boolean wasQuoted) {
        String value = cell.toString();
        return wasQuoted ? value : value.trim();
    }

    private static boolean isEmptyRow(List<String> row) {
        if (row.isEmpty()) {
            return true;
        }
        for (String cell : row) {
            if (!cell.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
