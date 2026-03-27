package com.telemetry.analyzer.parser;

import org.apache.commons.csv.CSVFormat;

import java.nio.charset.StandardCharsets;

/**
 * Detects UTF-8 BOM, delimiter (comma vs semicolon — common in MoTeC / EU exports).
 */
public final class CsvFormatSniffer {

    private CsvFormatSniffer() {
    }

    public static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    public static String textFromBytes(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    public static char detectDelimiter(String firstNonEmptyLine) {
        if (firstNonEmptyLine == null || firstNonEmptyLine.isBlank()) {
            return ',';
        }
        int commas = countChar(firstNonEmptyLine, ',');
        int semis = countChar(firstNonEmptyLine, ';');
        int tabs = countChar(firstNonEmptyLine, '\t');
        if (tabs >= commas && tabs >= semis && tabs > 0) {
            return '\t';
        }
        return semis > commas ? ';' : ',';
    }

    public static CSVFormat.Builder baseFormat(char delimiter) {
        return CSVFormat.Builder.create(delimiter);
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
