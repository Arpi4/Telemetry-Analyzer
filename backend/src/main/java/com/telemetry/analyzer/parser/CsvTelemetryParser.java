package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class CsvTelemetryParser implements TelemetryParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    @Override
    public SessionData parse(byte[] content, String sourceFile, ImportReport report) {
        List<TelemetryPoint> points = new ArrayList<>();
        try {
            String text = CsvFormatSniffer.stripBom(CsvFormatSniffer.textFromBytes(content));
            String firstLine = text.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
            char delimiter = CsvFormatSniffer.detectDelimiter(firstLine);

            CSVFormat format = CsvFormatSniffer.baseFormat(delimiter)
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(true)
                    .build();

            try (CSVParser csvParser = new CSVParser(new StringReader(text), format)) {
                Map<String, Integer> headers = csvParser.getHeaderMap();
                if (headers == null || headers.isEmpty()) {
                    report.addError("CSV has no parseable header row.");
                    return emptySession(sourceFile);
                }

                String tsKey = TelemetryChannelResolver.findTime(headers);
                String speedKey = TelemetryChannelResolver.findSpeed(headers);
                String throttleKey = TelemetryChannelResolver.findThrottle(headers);
                String brakeKey = TelemetryChannelResolver.findBrake(headers);
                String gearKey = TelemetryChannelResolver.findGear(headers);
                String latKey = TelemetryChannelResolver.findLat(headers);
                String lonKey = TelemetryChannelResolver.findLon(headers);

                if (speedKey == null || throttleKey == null || brakeKey == null || gearKey == null) {
                    report.addError("Missing required channels in CSV. Need speed, throttle, brake, and gear "
                            + "(MoTeC exports: e.g. Speed, Throttle Pos, Brake Pos, Gear).");
                    return emptySession(sourceFile);
                }

                if (looksLikeMotec(headers, sourceFile)) {
                    report.addWarning("MoTeC-style CSV header detected.");
                }

                int skipped = 0;
                for (CSVRecord rec : csvParser) {
                    try {
                        double timestamp = parseTimestamp(rec, tsKey, rec.getRecordNumber());
                        double speed = parseRequiredDouble(rec, speedKey);
                        double throttle = parseRequiredDouble(rec, throttleKey);
                        double brake = parseRequiredDouble(rec, brakeKey);
                        int gear = (int) Math.round(parseRequiredDouble(rec, gearKey));
                        Double lat = parseOptionalDouble(rec, latKey);
                        Double lon = parseOptionalDouble(rec, lonKey);
                        points.add(new TelemetryPoint(timestamp, speed, throttle, brake, gear, lat, lon));
                    } catch (Exception ex) {
                        skipped++;
                    }
                }
                if (skipped > 0) {
                    report.addWarning("Skipped " + skipped + " malformed CSV rows.");
                }
            }
        } catch (Exception ex) {
            report.addError("Failed to parse CSV: " + ex.getMessage());
        }

        if (points.isEmpty()) {
            report.addWarning("No points parsed from CSV.");
        }

        return new SessionData(
                buildSessionId(sourceFile),
                sourceFile,
                Instant.now(),
                List.of(new LapData("lap-1", points))
        );
    }

    private static boolean looksLikeMotec(Map<String, Integer> headers, String sourceFile) {
        if (sourceFile.toLowerCase(Locale.ROOT).contains("motec")) {
            return true;
        }
        for (String h : headers.keySet()) {
            String lower = h.toLowerCase(Locale.ROOT);
            if (lower.contains("motec") || lower.contains("i2") || lower.contains("throttle pos")
                    || lower.contains("brake pos") || lower.contains("gps lat")) {
                return true;
            }
        }
        return false;
    }

    private static double parseTimestamp(CSVRecord rec, String tsKey, long rowNo) {
        if (tsKey == null) {
            return rowNo;
        }
        return parseRequiredDouble(rec, tsKey);
    }

    private static double parseRequiredDouble(CSVRecord rec, String key) {
        String raw = rec.get(key);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty cell");
        }
        return parseDoubleLoose(raw);
    }

    private static Double parseOptionalDouble(CSVRecord rec, String key) {
        if (key == null) {
            return null;
        }
        String raw = rec.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseDoubleLoose(raw);
    }

    /**
     * Supports EU decimal comma and stray spaces / percent signs from exports.
     */
    static double parseDoubleLoose(String raw) {
        String v = raw.trim().replace("%", "").replace(" ", "").replace(',', '.');
        return Double.parseDouble(v);
    }

    private static String buildSessionId(String sourceFile) {
        return "session-" + Math.abs(sourceFile.hashCode()) + "-" + System.currentTimeMillis();
    }

    private static SessionData emptySession(String sourceFile) {
        return new SessionData(buildSessionId(sourceFile), sourceFile, Instant.now(), List.of());
    }
}
