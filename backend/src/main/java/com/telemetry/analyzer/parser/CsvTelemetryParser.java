package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CsvTelemetryParser implements TelemetryParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    @Override
    public SessionData parse(byte[] content, String sourceFile, ImportReport report) {
        List<TelemetryPoint> points = new ArrayList<>();
        try (CSVParser csvParser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
                .parse(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            Map<String, Integer> headers = csvParser.getHeaderMap();
            String tsKey = findHeader(headers, "timestamp", "time", "t");
            String speedKey = findHeader(headers, "speed");
            String throttleKey = findHeader(headers, "throttle", "gas");
            String brakeKey = findHeader(headers, "brake");
            String gearKey = findHeader(headers, "gear");
            String latKey = findHeader(headers, "lat", "latitude", "gps_lat");
            String lonKey = findHeader(headers, "lon", "longitude", "gps_lon");

            if (speedKey == null || throttleKey == null || brakeKey == null || gearKey == null) {
                report.addError("Missing required channels in CSV. Required: speed, throttle, brake, gear");
                return emptySession(sourceFile);
            }

            int skipped = 0;
            for (CSVRecord rec : csvParser) {
                try {
                    double timestamp = parseDouble(rec, tsKey, rec.getRecordNumber());
                    double speed = parseDouble(rec, speedKey, rec.getRecordNumber());
                    double throttle = parseDouble(rec, throttleKey, rec.getRecordNumber());
                    double brake = parseDouble(rec, brakeKey, rec.getRecordNumber());
                    int gear = (int) parseDouble(rec, gearKey, rec.getRecordNumber());
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

    private static String buildSessionId(String sourceFile) {
        return "session-" + Math.abs(sourceFile.hashCode()) + "-" + System.currentTimeMillis();
    }

    private static SessionData emptySession(String sourceFile) {
        return new SessionData(buildSessionId(sourceFile), sourceFile, Instant.now(), List.of());
    }

    private static String findHeader(Map<String, Integer> headers, String... candidates) {
        Map<String, String> lowered = headers.keySet().stream()
                .collect(Collectors.toMap(k -> k.toLowerCase(Locale.ROOT), k -> k));
        for (String candidate : candidates) {
            String key = lowered.get(candidate.toLowerCase(Locale.ROOT));
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    private static double parseDouble(CSVRecord rec, String key, long rowNo) {
        if (key == null) {
            return rowNo;
        }
        return Double.parseDouble(rec.get(key));
    }

    private static Double parseOptionalDouble(CSVRecord rec, String key) {
        if (key == null) {
            return null;
        }
        String value = rec.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Double.parseDouble(value);
    }
}
