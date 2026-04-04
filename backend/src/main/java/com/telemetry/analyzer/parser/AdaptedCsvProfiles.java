package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MoTeC i2 "CSV workbook" exports (preamble + unit row) and OpenF1 laps API CSV (malformed first column quotes).
 */
public final class AdaptedCsvProfiles {

    /** OpenF1 CSV often omits the closing quote after the timestamp. */
    private static final Pattern OPENF1_LEADING_DATE = Pattern.compile(
            "^\"(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\+\\d{2}:\\d{2}),(.*)$");

    private AdaptedCsvProfiles() {
    }

    public static boolean isMoTeCWorkbookExport(String text) {
        String head = text.lines().limit(25).reduce("", (a, b) -> a + b + "\n").toLowerCase(Locale.ROOT);
        return head.contains("motec csv file") || head.contains("\"format\",\"motec")
                || head.startsWith("\"format\"");
    }

    public static boolean isOpenF1LapsCsv(String text) {
        String first = text.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
        return first.contains("lap_number") && first.contains("lap_duration")
                && (first.contains("i1_speed") || first.contains("i2_speed") || first.contains("st_speed"));
    }

    /**
     * OpenF1 exports often omit the closing quote after the ISO timestamp; repair so RFC4180 parsers work.
     */
    public static String repairOpenF1LapsCsvText(String text) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String line : text.split("\\R", -1)) {
            if (!first) {
                out.append('\n');
            }
            first = false;
            if (line.startsWith("\"")) {
                Matcher m = OPENF1_LEADING_DATE.matcher(line);
                if (m.matches()) {
                    out.append('"').append(m.group(1)).append('"').append(',').append(m.group(2));
                    continue;
                }
            }
            out.append(line);
        }
        return out.toString();
    }

    public static SessionData parseMoTeCWorkbook(String text, String sourceFile, ImportReport report) {
        List<String> lines = text.lines().toList();
        int headerIdx = findMotecChannelHeaderLineIndex(lines);
        if (headerIdx < 0) {
            report.addError("MoTeC CSV: could not find channel header row (expected Time, Speed, Throttle, …).");
            return emptySession(sourceFile);
        }

        List<String> columnNames;
        try {
            columnNames = parseCsvLineToValues(lines.get(headerIdx), ',');
        } catch (Exception e) {
            report.addError("MoTeC CSV: failed to parse header row: " + e.getMessage());
            return emptySession(sourceFile);
        }

        columnNames = dedupeColumnNames(columnNames);
        int dataStart = headerIdx + 1;
        while (dataStart < lines.size() && isMotecUnitRow(lines.get(dataStart), columnNames.size())) {
            dataStart++;
        }

        int idxTime = indexOfName(columnNames, "time");
        int idxSpeed = indexOfName(columnNames, "speed");
        int idxThrottle = indexOfName(columnNames, "throttle");
        int idxBrake = indexOfName(columnNames, "brake");
        int idxGear = indexOfName(columnNames, "gear");
        if (idxSpeed < 0 || idxThrottle < 0 || idxBrake < 0 || idxGear < 0) {
            report.addError("MoTeC CSV: missing Speed / Throttle / Brake / Gear columns.");
            return emptySession(sourceFile);
        }

        int minCells = Math.max(Math.max(idxSpeed, idxThrottle), Math.max(idxBrake, idxGear)) + 1;

        List<TelemetryPoint> points = new ArrayList<>();
        int skipped = 0;
        for (int i = dataStart; i < lines.size(); i++) {
            String rawLine = lines.get(i).trim();
            if (rawLine.isEmpty()) {
                continue;
            }
            try {
                List<String> cells = parseCsvLineToValues(rawLine, ',');
                if (cells.size() < minCells) {
                    skipped++;
                    continue;
                }
                while (cells.size() < columnNames.size()) {
                    cells.add("");
                }
                double t = idxTime >= 0 ? CsvTelemetryParser.parseDoubleLoose(cells.get(idxTime)) : points.size() * 0.01;
                double speed = CsvTelemetryParser.parseDoubleLoose(cells.get(idxSpeed));
                double throttle = CsvTelemetryParser.parseDoubleLoose(cells.get(idxThrottle));
                double brake = CsvTelemetryParser.parseDoubleLoose(cells.get(idxBrake));
                String gearCell = cells.get(idxGear).trim().replace("\"", "");
                int gear = parseGearCell(gearCell);
                points.add(new TelemetryPoint(t, speed, throttle, brake, gear, null, null));
            } catch (Exception ex) {
                skipped++;
            }
        }

        if (skipped > 0) {
            report.addWarning("MoTeC CSV: skipped " + skipped + " rows.");
        }
        report.addWarning("Parsed MoTeC workbook CSV (metadata / unit rows stripped).");

        return new SessionData(
                buildSessionId(sourceFile),
                sourceFile,
                Instant.now(),
                List.of(new LapData("lap-1", points))
        );
    }

    private static int parseGearCell(String gearCell) {
        if (gearCell.isEmpty()) {
            return 0;
        }
        try {
            return (int) Math.round(Double.parseDouble(gearCell.replace(',', '.')));
        } catch (NumberFormatException e) {
            return gearCell.hashCode() % 8 + 1;
        }
    }

    public static SessionData parseOpenF1Laps(String text, String sourceFile, ImportReport report) {
        String fixed = repairOpenF1LapsCsvText(text);
        char delimiter = ',';
        CSVFormat format = CsvFormatSniffer.baseFormat(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

        List<TelemetryPoint> points = new ArrayList<>();
        int skipped = 0;
        try (CSVParser csvParser = new CSVParser(new StringReader(fixed), format)) {
            Map<String, Integer> headerMap = csvParser.getHeaderMap();
            if (headerMap == null || headerMap.isEmpty()) {
                report.addError("OpenF1 laps CSV: no header.");
                return emptySession(sourceFile);
            }

            String lapNumKey = findColumnKey(headerMap, "lap_number");
            String i1Key = findColumnKey(headerMap, "i1_speed");
            String i2Key = findColumnKey(headerMap, "i2_speed");
            String stKey = findColumnKey(headerMap, "st_speed");
            String pitKey = findColumnKey(headerMap, "is_pit_out_lap");

            if (lapNumKey == null) {
                report.addError("OpenF1 laps CSV: lap_number column not found.");
                return emptySession(sourceFile);
            }

            for (CSVRecord rec : csvParser) {
                try {
                    double lapNo = parseDoubleSafe(rec, lapNumKey, -1);
                    if (lapNo < 0) {
                        skipped++;
                        continue;
                    }
                    double avgSpeed = averageSpeedKmh(rec, i1Key, i2Key, stKey);
                    if (Double.isNaN(avgSpeed) || avgSpeed <= 0) {
                        avgSpeed = 0;
                    }
                    boolean pit = parseBoolCell(rec, pitKey);
                    double throttle = Math.min(100, Math.max(0, avgSpeed / 3.3));
                    double brake = pit ? 45 : 8;
                    int gear = (int) (Math.abs(lapNo) % 8) + 1;
                    points.add(new TelemetryPoint(lapNo, avgSpeed, throttle, brake, gear, null, null));
                } catch (Exception ex) {
                    skipped++;
                }
            }
        } catch (Exception ex) {
            report.addError("OpenF1 laps CSV: " + ex.getMessage());
            return emptySession(sourceFile);
        }

        if (skipped > 0) {
            report.addWarning("OpenF1 laps: skipped " + skipped + " rows.");
        }
        report.addWarning("OpenF1 laps summary CSV: one synthetic sample per lap (speed ≈ sector speeds average).");

        return new SessionData(
                buildSessionId(sourceFile),
                sourceFile,
                Instant.now(),
                List.of(new LapData("lap-1", points))
        );
    }

    private static double parseDoubleSafe(CSVRecord rec, String key, double defaultVal) {
        if (key == null) {
            return defaultVal;
        }
        String raw = rec.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultVal;
        }
        try {
            return CsvTelemetryParser.parseDoubleLoose(raw);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static boolean parseBoolCell(CSVRecord rec, String key) {
        if (key == null) {
            return false;
        }
        String v = rec.get(key);
        if (v == null) {
            return false;
        }
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("1");
    }

    private static double averageSpeedKmh(CSVRecord rec, String i1, String i2, String st) {
        double sum = 0;
        int n = 0;
        for (String k : List.of(i1, i2, st)) {
            if (k == null) {
                continue;
            }
            String raw = rec.get(k);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                sum += CsvTelemetryParser.parseDoubleLoose(raw);
                n++;
            } catch (Exception ignored) {
                // skip
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private static String findColumnKey(Map<String, Integer> headerMap, String logical) {
        for (String raw : headerMap.keySet()) {
            if (raw != null && raw.trim().equalsIgnoreCase(logical)) {
                return raw;
            }
        }
        return null;
    }

    private static int findMotecChannelHeaderLineIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l == null || l.isBlank()) {
                continue;
            }
            String lower = l.toLowerCase(Locale.ROOT);
            if (!lower.contains("time") || !lower.contains("speed")) {
                continue;
            }
            if (!lower.contains("throttle") || !lower.contains("brake") || !lower.contains("gear")) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean isMotecUnitRow(String line, int expectedCols) {
        String t = line.trim();
        if (t.isEmpty()) {
            return false;
        }
        try {
            List<String> cells = parseCsvLineToValues(t, ',');
            if (cells.isEmpty()) {
                return false;
            }
            String c0 = cells.get(0).replace("\"", "").trim().toLowerCase(Locale.ROOT);
            return "s".equals(c0) || "sec".equals(c0) || "seconds".equals(c0);
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> parseCsvLineToValues(String line, char delimiter) throws Exception {
        CSVFormat fmt = CsvFormatSniffer.baseFormat(delimiter).build();
        try (CSVParser p = new CSVParser(new StringReader(line), fmt)) {
            for (CSVRecord r : p) {
                List<String> out = new ArrayList<>();
                for (int i = 0; i < r.size(); i++) {
                    out.add(r.get(i));
                }
                return out;
            }
        }
        return List.of();
    }

    private static List<String> dedupeColumnNames(List<String> names) {
        Map<String, Integer> seenCount = new HashMap<>();
        List<String> out = new ArrayList<>();
        for (String n : names) {
            String base = n == null ? "" : n.replace("\"", "").trim();
            String key = base.toLowerCase(Locale.ROOT);
            int c = seenCount.merge(key, 1, Integer::sum);
            if (c > 1) {
                base = base + "_" + c;
            }
            out.add(base);
        }
        return out;
    }

    private static int indexOfName(List<String> names, String logical) {
        for (int i = 0; i < names.size(); i++) {
            if (logical.equalsIgnoreCase(names.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String buildSessionId(String sourceFile) {
        return "session-" + Math.abs(sourceFile.hashCode()) + "-" + System.currentTimeMillis();
    }

    private static SessionData emptySession(String sourceFile) {
        return new SessionData(buildSessionId(sourceFile), sourceFile, Instant.now(), List.of());
    }
}
