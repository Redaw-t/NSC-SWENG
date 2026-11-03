package system.service;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Writes the log exports: CSV files that open correctly in Excel. */
final class Csv {

    /** A plain number such as -2 or +1,250.50: safe in Excel, and must stay a number there. */
    private static final Pattern NUMBER = Pattern.compile("^[+-]?[0-9][0-9,]*(\\.[0-9]+)?$");

    private Csv() { }

    /**
     * Writes {@code header} and {@code rows} to {@code file}.
     *
     * @throws SystemException the file could not be written
     */
    static void write(Path file, List<String> header, List<List<String>> rows) throws SystemException {
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write('﻿');   // tells Excel the file is UTF-8 (names like Peña)
            out.write(line(header));
            for (List<String> row : rows) {
                out.write(line(row));
            }
        } catch (IOException e) {
            throw new SystemException("The file could not be saved. Check that it is not open in another "
                    + "program and that the folder can be written to.", e);
        }
    }

    /** One CSV line: every value quoted when it needs to be, ending in CRLF (as Excel writes it). */
    private static String line(List<String> values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(value(values.get(i)));
        }
        return line.append("\r\n").toString();
    }

    private static String value(String value) {
        if (value == null) {
            return "";
        }
        // A leading = + - @ (or a tab or line break before one) would make Excel run the text as a formula.
        boolean risky = !value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0)) >= 0;
        String safe = risky && !NUMBER.matcher(value).matches() ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
