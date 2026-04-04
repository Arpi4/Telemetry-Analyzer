package com.telemetry.analyzer.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180-style field splitting (quoted fields, {@code ""} escape). Tolerant of OpenF1 / pandas odd quoting.
 */
public final class Rfc4180CsvLineSplitter {

    private Rfc4180CsvLineSplitter() {
    }

    public static List<String> split(String line, char delimiter) {
        List<String> result = new ArrayList<>();
        if (line == null) {
            return result;
        }
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == delimiter) {
                    result.add(field.toString());
                    field.setLength(0);
                } else {
                    field.append(c);
                }
            }
        }
        result.add(field.toString());
        return result;
    }
}
