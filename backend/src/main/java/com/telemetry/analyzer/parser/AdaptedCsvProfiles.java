package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.domain.TelemetryPoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MoTeC i2 workbook CSV (preamble + unit row) and OpenF1 laps export (odd quoting in segment columns).
 */
public final class AdaptedCsvProfiles {

    private static final int MAX_MOTEC_POINTS_STORED = 35_000;

    /** OpenF1: missing closing quote after ISO timestamp with numeric timezone offset. */
    private static final Pattern OPENF1_LEADING_DATE_OFFSET = Pattern.compile(
            "^\"(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\+\\d{2}:\\d{2}),(.*)$");

    /** OpenF1: same with Z suffix. */
    private static final Pattern OPENF1_LEADING_DATE_Z = Pattern.compile(
            "^\"(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z),(.*)$");

    private AdaptedCsvProfiles() {
    }

    public static boolean isOpenF1LapsCsv(String text) {
        String first = text.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
        return first.contains("lap_number") && first.contains("lap_duration")
                && (first.contains("i1_speed") || first.contains("i2_speed") || first.contains("st_speed"));
    }

    /**
     * Channel-row layout: Time + Speed + Throttle + Brake + Gear (MoTeC export or compatible telemetry).
     * Excludes OpenF1 lap summaries (no throttle/brake in header).
     */
    public static boolean hasMotecStyleChannelHeader(String text) {
        if (isOpenF1LapsCsv(text)) {
            return false;
        }
        List<String> lines = text.lines().limit(4000).toList();
        return findMotecChannelHeaderLineIndex(lines) >= 0;
    }

    public static String repairOpenF1LapsCsvText(String text) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String line : text.split("\\R", -1)) {
            if (!first) {
                out.append('\n');
            }
            first = false;
            out.append(repairOpenF1SingleLine(line));
        }
        return out.toString();
    }

    static String repairOpenF1SingleLine(String line) {
        if (!line.startsWith("\"")) {
            return line;
        }
        Matcher m = OPENF1_LEADING_DATE_OFFSET.matcher(line);
        if (m.matches()) {
            return '"' + m.group(1) + '"' + ',' + m.group(2);
        }
        m = OPENF1_LEADING_DATE_Z.matcher(line);
        if (m.matches()) {
            return '"' + m.group(1) + '"' + ',' + m.group(2);
        }
        return line;
    }

    public static SessionData parseMoTeCWorkbook(String text, String sourceFile, ImportReport report) {
        List<String> lines = text.lines().toList();
        int headerIdx = findMotecChannelHeaderLineIndex(lines);
        if (headerIdx < 0) {
            report.addError("MoTeC CSV: could not find channel header row (Time, Speed, Throttle, Brake, Gear).");
            return emptySession(sourceFile);
        }

        List<String> columnNames = Rfc4180CsvLineSplitter.split(lines.get(headerIdx).trim(), ',');
        columnNames = trimAll(columnNames);
        columnNames = dedupeColumnNames(columnNames);

        int dataStart = headerIdx + 1;
        while (dataStart < lines.size() && isMotecUnitRow(lines.get(dataStart))) {
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
        boolean truncated = false;
        for (int i = dataStart; i < lines.size(); i++) {
            if (points.size() >= MAX_MOTEC_POINTS_STORED) {
                truncated = true;
                break;
            }
            String rawLine = lines.get(i).trim();
            if (rawLine.isEmpty()) {
                continue;
            }
            try {
                List<String> cells = Rfc4180CsvLineSplitter.split(rawLine, ',');
                cells = trimAll(cells);
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
                String gearCell = cells.get(idxGear).replace("\"", "");
                int gear = parseGearCell(gearCell);
                points.add(new TelemetryPoint(t, speed, throttle, brake, gear, null, null));
            } catch (Exception ex) {
                skipped++;
            }
        }

        if (truncated) {
            report.addWarning("MoTeC CSV: stored first " + MAX_MOTEC_POINTS_STORED + " samples only (file truncated for server limits).");
        }

        if (skipped > 0) {
            report.addWarning("MoTeC CSV: skipped " + skipped + " rows.");
        }
        report.addWarning("Parsed channel-row CSV (MoTeC workbook or compatible).");

        return new SessionData(
                buildSessionId(sourceFile),
                sourceFile,
                Instant.now(),
                List.of(new LapData("lap-1", points))
        );
    }

    private static List<String> trimAll(List<String> cells) {
        List<String> out = new ArrayList<>(cells.size());
        for (String c : cells) {
            out.add(c == null ? "" : c.trim());
        }
        return out;
    }

    private static int parseGearCell(String gearCell) {
        if (gearCell == null || gearCell.isBlank()) {
            return 0;
        }
        try {
            return (int) Math.round(Double.parseDouble(gearCell.replace(',', '.').trim()));
        } catch (NumberFormatException e) {
            return Math.abs(gearCell.hashCode() % 8) + 1;
        }
    }

    /**
     * OpenF1: do not use Apache CSV for the whole file — segment columns break the parser.
     */
    public static SessionData parseOpenF1Laps(String text, String sourceFile, ImportReport report) {
        List<String> lines = text.lines().toList();
        int hi = 0;
        while (hi < lines.size() && lines.get(hi).isBlank()) {
            hi++;
        }
        if (hi >= lines.size()) {
            report.addError("OpenF1 laps CSV: empty file.");
            return emptySession(sourceFile);
        }

        String headerLine = lines.get(hi).trim();
        List<String> headers = splitSimpleHeader(headerLine);
        if (headers.isEmpty()) {
            report.addError("OpenF1 laps CSV: empty header.");
            return emptySession(sourceFile);
        }

        int lapNumIx = indexOfHeader(headers, "lap_number");
        int i1Ix = indexOfHeader(headers, "i1_speed");
        int i2Ix = indexOfHeader(headers, "i2_speed");
        int stIx = indexOfHeader(headers, "st_speed");
        int pitIx = indexOfHeader(headers, "is_pit_out_lap");

        if (lapNumIx < 0) {
            report.addError("OpenF1 laps CSV: lap_number column not found.");
            return emptySession(sourceFile);
        }

        List<TelemetryPoint> points = new ArrayList<>();
        int skipped = 0;

        for (int li = hi + 1; li < lines.size(); li++) {
            String raw = lines.get(li).trim();
            if (raw.isEmpty()) {
                continue;
            }
            String line = repairOpenF1SingleLine(raw);
            try {
                List<String> cells = Rfc4180CsvLineSplitter.split(line, ',');
                cells = trimAll(cells);
                while (cells.size() < headers.size()) {
                    cells.add("");
                }
                if (cells.size() < lapNumIx + 1) {
                    skipped++;
                    continue;
                }

                double lapNo = parseDoubleAt(cells, lapNumIx, -1);
                if (lapNo < 0) {
                    skipped++;
                    continue;
                }

                double avgSpeed = averageSpeedAt(cells, i1Ix, i2Ix, stIx);
                if (Double.isNaN(avgSpeed) || avgSpeed <= 0) {
                    avgSpeed = 0;
                }
                boolean pit = parseBoolAt(cells, pitIx);
                double throttle = Math.min(100, Math.max(0, avgSpeed / 3.3));
                double brake = pit ? 45 : 8;
                int gear = (int) (Math.abs(lapNo) % 8) + 1;
                points.add(new TelemetryPoint(lapNo, avgSpeed, throttle, brake, gear, null, null));
            } catch (Exception ex) {
                skipped++;
            }
        }

        if (skipped > 0) {
            report.addWarning("OpenF1 laps: skipped " + skipped + " rows.");
        }
        report.addWarning("OpenF1 laps summary: one synthetic sample per lap (sector speed average).");

        return new SessionData(
                buildSessionId(sourceFile),
                sourceFile,
                Instant.now(),
                List.of(new LapData("lap-1", points))
        );
    }

    private static List<String> splitSimpleHeader(String headerLine) {
        List<String> parts = new ArrayList<>();
        for (String p : headerLine.split(",", -1)) {
            parts.add(p.trim());
        }
        return parts;
    }

    private static int indexOfHeader(List<String> headers, String name) {
        for (int i = 0; i < headers.size(); i++) {
            if (name.equalsIgnoreCase(headers.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static double parseDoubleAt(List<String> cells, int ix, double defaultVal) {
        if (ix < 0 || ix >= cells.size()) {
            return defaultVal;
        }
        String raw = cells.get(ix);
        if (raw == null || raw.isBlank()) {
            return defaultVal;
        }
        try {
            return CsvTelemetryParser.parseDoubleLoose(raw);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static boolean parseBoolAt(List<String> cells, int ix) {
        if (ix < 0 || ix >= cells.size()) {
            return false;
        }
        String v = cells.get(ix);
        if (v == null) {
            return false;
        }
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("1");
    }

    private static double averageSpeedAt(List<String> cells, int i1, int i2, int st) {
        double sum = 0;
        int n = 0;
        for (int ix : List.of(i1, i2, st)) {
            if (ix < 0 || ix >= cells.size()) {
                continue;
            }
            String raw = cells.get(ix);
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

    private static boolean isMotecUnitRow(String line) {
        String t = line.trim();
        if (t.isEmpty()) {
            return false;
        }
        List<String> cells = Rfc4180CsvLineSplitter.split(t, ',');
        if (cells.isEmpty()) {
            return false;
        }
        String c0 = cells.get(0).replace("\"", "").trim().toLowerCase(Locale.ROOT);
        return "s".equals(c0) || "sec".equals(c0) || "seconds".equals(c0);
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
