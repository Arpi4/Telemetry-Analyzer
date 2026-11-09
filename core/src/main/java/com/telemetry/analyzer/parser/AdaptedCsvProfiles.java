package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.domain.TelemetryPoint;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MoTeC i2 workbook CSV (preamble + unit row) and OpenF1 laps export (odd quoting in segment columns).
 */
public final class AdaptedCsvProfiles {

    private static final int MAX_MOTEC_POINTS_STORED = 200_000;

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
        List<Double> beaconMarkersSeconds = new ArrayList<>();
        List<String> columnNames = null;
        int idxTime = -1;
        int idxSpeed = -1;
        int idxThrottle = -1;
        int idxBrake = -1;
        int idxGear = -1;
        int idxDist = -1;
        int idxYaw = -1;
        int idxGpsLat = -1;
        int idxGpsLon = -1;
        int idxLatAcc = -1;
        int idxLonAcc = -1;
        int idxSteer = -1;
        int idxRpm = -1;
        List<String> columnUnits = null;
        int minCells = -1;

        List<TelemetryPoint> points = new ArrayList<>();
        DoublePool pool = new DoublePool();
        int skipped = 0;
        boolean truncated = false;
        char delimiter = ',';

        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            boolean readingData = false;
            while ((line = reader.readLine()) != null) {
                String rawLine = line.trim();
                if (rawLine.isEmpty()) {
                    continue;
                }

                if (!readingData) {
                    beaconMarkersSeconds.addAll(parseBeaconMarkersFromLine(rawLine));
                    if (!isMotecHeaderCandidate(rawLine)) {
                        continue;
                    }
                    // MoTeC i2 writes commas; EU exports of the same layout often use semicolons.
                    delimiter = CsvFormatSniffer.detectDelimiter(rawLine);
                    columnNames = Rfc4180CsvLineSplitter.split(rawLine, delimiter);
                    columnNames = trimAll(columnNames);
                    columnNames = dedupeColumnNames(columnNames);
                    Map<String, Integer> headerMap = headerMap(columnNames);

                    idxTime = columnIndex(headerMap, TelemetryChannelResolver.findTime(headerMap));
                    idxSpeed = columnIndex(headerMap, TelemetryChannelResolver.findSpeed(headerMap));
                    idxThrottle = columnIndex(headerMap, TelemetryChannelResolver.findThrottle(headerMap));
                    idxBrake = columnIndex(headerMap, TelemetryChannelResolver.findBrake(headerMap));
                    idxGear = columnIndex(headerMap, TelemetryChannelResolver.findGear(headerMap));
                    idxDist = columnIndex(headerMap, TelemetryChannelResolver.findDistance(headerMap));
                    idxYaw = columnIndex(headerMap, TelemetryChannelResolver.findYawRate(headerMap));
                    idxGpsLat = findGpsLatitudeColumnIndex(columnNames);
                    idxGpsLon = findGpsLongitudeColumnIndex(columnNames);
                    idxLatAcc = columnIndex(headerMap, TelemetryChannelResolver.findLateralAcceleration(headerMap));
                    idxLonAcc = columnIndex(headerMap, TelemetryChannelResolver.findLongitudinalAcceleration(headerMap));
                    idxSteer = columnIndex(headerMap, TelemetryChannelResolver.findSteeringAngle(headerMap));
                    idxRpm = columnIndex(headerMap, TelemetryChannelResolver.findEngineRpm(headerMap));
                    if (idxSpeed < 0 || idxThrottle < 0 || idxBrake < 0 || idxGear < 0) {
                        report.addError("MoTeC CSV: missing Speed / Throttle / Brake / Gear columns.");
                        return emptySession(sourceFile);
                    }
                    minCells = Math.max(Math.max(idxSpeed, idxThrottle), Math.max(idxBrake, idxGear)) + 1;
                    readingData = true;
                    continue;
                }

                if (isMotecUnitRow(rawLine, delimiter)) {
                    if (columnUnits == null) {
                        columnUnits = trimAll(Rfc4180CsvLineSplitter.split(rawLine, delimiter));
                    }
                    continue;
                }
                if (points.size() >= MAX_MOTEC_POINTS_STORED) {
                    truncated = true;
                    break;
                }
                try {
                    List<String> cells = Rfc4180CsvLineSplitter.split(rawLine, delimiter);
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
                    Double lat = parseGeoLat(cells, idxGpsLat);
                    Double lon = parseGeoLon(cells, idxGpsLon);
                    Double dist = idxDist >= 0 ? parseOptionalDoubleCell(cells, idxDist) : null;
                    Double yaw = idxYaw >= 0 ? parseOptionalDoubleCell(cells, idxYaw) : null;
                    points.add(new TelemetryPoint(t, speed, throttle, brake, gear, lat, lon, dist, yaw,
                            pool.share(parseOptionalDoubleCell(cells, idxLatAcc)),
                            pool.share(parseOptionalDoubleCell(cells, idxLonAcc)),
                            pool.share(parseOptionalDoubleCell(cells, idxSteer)),
                            pool.share(parseOptionalDoubleCell(cells, idxRpm))));
                } catch (Exception ex) {
                    skipped++;
                }
            }
        } catch (IOException ex) {
            report.addError("MoTeC CSV read failed: " + ex.getMessage());
            return emptySession(sourceFile);
        }

        if (columnNames == null) {
            report.addError("MoTeC CSV: could not find channel header row (Time, Speed, Throttle, Brake, Gear).");
            return emptySession(sourceFile);
        }

        if (truncated) {
            report.addWarning("MoTeC CSV: stored first " + MAX_MOTEC_POINTS_STORED + " samples only (file truncated for server limits).");
        }

        if (skipped > 0) {
            report.addWarning("MoTeC CSV: skipped " + skipped + " rows.");
        }
        report.addWarning("Parsed channel-row CSV (MoTeC workbook or compatible).");
        ChannelUnits.normalizeChannels(points, column(columnNames, columnUnits, idxLatAcc),
                column(columnNames, columnUnits, idxLonAcc), column(columnNames, columnUnits, idxSteer),
                columnNames.get(idxSpeed), "MoTeC CSV", report);
        List<LapData> laps = splitPointsIntoLapsByBeaconMarkers(points, beaconMarkersSeconds);
        if (laps.size() > 1) {
            report.addWarning("Detected " + laps.size() + " laps using MoTeC beacon markers.");
        }

        return new SessionData(
                buildSessionId(sourceFile),
                sourceFile,
                Instant.now(),
                laps
        );
    }

    private static List<LapData> splitPointsIntoLapsByBeaconMarkers(List<TelemetryPoint> points, List<Double> beaconMarkersSeconds) {
        if (points.isEmpty()) {
            return List.of(new LapData("lap-1", List.of()));
        }
        if (beaconMarkersSeconds.isEmpty()) {
            return List.of(new LapData("lap-1", rebaseDistance(points)));
        }

        List<LapData> laps = new ArrayList<>();
        int startIdx = 0;
        int lapNo = 1;

        for (double marker : beaconMarkersSeconds) {
            int markerIdx = firstIndexAtOrAfter(points, marker);
            if (markerIdx <= startIdx || markerIdx > points.size()) {
                continue;
            }
            // The first sample of the next lap also closes this one, so last - first timestamp is
            // the full lap time instead of being one sample period short.
            List<TelemetryPoint> lapPoints = points.subList(startIdx, Math.min(markerIdx + 1, points.size()));
            if (!lapPoints.isEmpty()) {
                laps.add(new LapData("lap-" + lapNo, rebaseDistance(lapPoints)));
                lapNo++;
            }
            startIdx = markerIdx;
        }

        if (startIdx < points.size()) {
            List<TelemetryPoint> lapPoints = points.subList(startIdx, points.size());
            if (!lapPoints.isEmpty()) {
                laps.add(new LapData("lap-" + lapNo, rebaseDistance(lapPoints)));
            }
        }

        return laps.isEmpty() ? List.of(new LapData("lap-1", rebaseDistance(points))) : laps;
    }

    /**
     * MoTeC exports the Distance channel cumulatively over the whole outing; analysis needs
     * distance from the start of each lap, otherwise laps 2..n cannot be overlaid. Always returns a
     * new list: a {@code subList} view kept by a lap would keep every parsed sample of the file alive.
     */
    static List<TelemetryPoint> rebaseDistance(List<TelemetryPoint> lapPoints) {
        Double origin = null;
        for (TelemetryPoint p : lapPoints) {
            if (p.distanceMeters() != null) {
                origin = p.distanceMeters();
                break;
            }
        }
        if (origin == null || origin == 0.0) {
            return new ArrayList<>(lapPoints);
        }
        List<TelemetryPoint> out = new ArrayList<>(lapPoints.size());
        for (TelemetryPoint p : lapPoints) {
            out.add(p.withDistance(p.distanceMeters() == null ? null : p.distanceMeters() - origin));
        }
        return out;
    }

    private static int firstIndexAtOrAfter(List<TelemetryPoint> points, double markerSeconds) {
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).timestamp() >= markerSeconds) {
                return i;
            }
        }
        return points.size();
    }

    private static List<Double> parseBeaconMarkersFromLine(String rawLine) {
        String lower = rawLine.toLowerCase(Locale.ROOT);
        if (!lower.contains("beacon markers")) {
            return List.of();
        }
        List<String> cells = Rfc4180CsvLineSplitter.split(rawLine.trim(), ',');
        if (cells.size() < 2) {
            return List.of();
        }
        String markerCell = cells.get(1).replace("\"", "").trim();
        if (markerCell.isEmpty()) {
            return List.of();
        }
        String[] tokens = markerCell.split("\\s+");
        List<Double> markers = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            try {
                markers.add(CsvTelemetryParser.parseDoubleLoose(token));
            } catch (Exception ignored) {
                // Ignore malformed marker values and keep parsing others.
            }
        }
        Collections.sort(markers);
        return markers;
    }

    private static boolean isMotecHeaderCandidate(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("time")
                && lower.contains("speed")
                && lower.contains("throttle")
                && lower.contains("brake")
                && lower.contains("gear");
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
        String g = gearCell.trim();
        if (g.equalsIgnoreCase("R")) {
            return -1;
        }
        try {
            return (int) Math.round(Double.parseDouble(g.replace(',', '.')));
        } catch (NumberFormatException e) {
            // "N" and any other non-numeric label: treat as neutral rather than dropping the sample.
            return 0;
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
                points.add(new TelemetryPoint(lapNo, avgSpeed, throttle, brake, gear, null, null, null));
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

    private static boolean isMotecUnitRow(String line, char delimiter) {
        String t = line.trim();
        if (t.isEmpty()) {
            return false;
        }
        List<String> cells = Rfc4180CsvLineSplitter.split(t, delimiter);
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

    /** Insertion-ordered, so the resolver prefers the first of duplicate names (e.g. "Time" before "TIME_2"). */
    private static Map<String, Integer> headerMap(List<String> names) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            map.putIfAbsent(names.get(i), i);
        }
        return map;
    }

    private static int columnIndex(Map<String, Integer> headerMap, String key) {
        return key == null ? -1 : headerMap.get(key);
    }

    /**
     * Optional channel column; its unit is the cell in the MoTeC unit row, else a "(unit)" suffix
     * of its header.
     */
    private static ChannelUnits.Column column(List<String> columnNames, List<String> columnUnits, int ix) {
        if (ix < 0) {
            return ChannelUnits.Column.ABSENT;
        }
        String name = columnNames.get(ix);
        if (columnUnits != null && ix < columnUnits.size()) {
            String unit = columnUnits.get(ix).replace("\"", "").trim();
            if (!unit.isEmpty() && !"..".equals(unit)) {
                return new ChannelUnits.Column(name, unit);
            }
        }
        return new ChannelUnits.Column(name, TelemetryChannelResolver.unitSuffix(name));
    }

    private static int findGpsLatitudeColumnIndex(List<String> names) {
        return findCoordinateColumnIndex(names,
                List.of("lat", "latitude", "gps_lat", "gps_latitude", "nav_lat", "nav_latitude"), "lat");
    }

    private static int findGpsLongitudeColumnIndex(List<String> names) {
        return findCoordinateColumnIndex(names,
                List.of("lon", "long", "longitude", "gps_lon", "gps_long", "gps_longitude",
                        "nav_lon", "nav_long", "nav_longitude"), "lon");
    }

    /**
     * Coordinate column: a whole name such as "Latitude" or "GPS_Lat" first, else the first GPS / nav
     * channel containing {@code fragment}. Accelerations and accuracies (MoTeC "G_LAT", AiM
     * "GPS LatAcc", "GPS Lateral G", "lat_accuracy") are never taken.
     */
    private static int findCoordinateColumnIndex(List<String> names, List<String> wholeNames, String fragment) {
        for (int i = 0; i < names.size(); i++) {
            if (wholeNames.contains(TelemetryChannelResolver.normalizeName(names.get(i)))) {
                return i;
            }
        }
        for (int i = 0; i < names.size(); i++) {
            String n = TelemetryChannelResolver.normalizeName(names.get(i));
            if ((n.contains("gps") || n.contains("nav")) && n.contains(fragment)
                    && !TelemetryChannelResolver.isAccelerationOrAccuracy(n)) {
                return i;
            }
        }
        return -1;
    }

    private static Double parseGeoLat(List<String> cells, int ix) {
        if (ix < 0 || ix >= cells.size()) {
            return null;
        }
        try {
            double v = CsvTelemetryParser.parseDoubleLoose(cells.get(ix));
            if (v < -90 || v > 90) {
                return null;
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static Double parseGeoLon(List<String> cells, int ix) {
        if (ix < 0 || ix >= cells.size()) {
            return null;
        }
        try {
            double v = CsvTelemetryParser.parseDoubleLoose(cells.get(ix));
            if (v < -180 || v > 180) {
                return null;
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static Double parseOptionalDoubleCell(List<String> cells, int ix) {
        if (ix < 0 || ix >= cells.size()) {
            return null;
        }
        try {
            String raw = cells.get(ix);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return CsvTelemetryParser.parseDoubleLoose(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildSessionId(String sourceFile) {
        return "session-" + Math.abs(sourceFile.hashCode()) + "-" + System.currentTimeMillis();
    }

    private static SessionData emptySession(String sourceFile) {
        return new SessionData(buildSessionId(sourceFile), sourceFile, Instant.now(), List.of());
    }
}