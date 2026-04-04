package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class GenericDelimitedParser {

    public SessionData parseTextTelemetry(String text, String sourceFile, ImportReport report) {
        String[] lines = text.split("\\R");
        if (lines.length < 2) {
            report.addError("Input does not contain enough rows to parse telemetry.");
            return new SessionData("session-empty", sourceFile, Instant.now(), List.of());
        }

        String delimiter = lines[0].contains(";") ? ";" : ",";
        String[] headers = lines[0].split(delimiter);

        int tsIdx = idxOf(headers, "timestamp", "time", "t");
        int speedIdx = idxOf(headers, "speed", "velocity", "kmh");
        int throttleIdx = idxOf(headers, "throttle", "gas", "accel");
        int brakeIdx = idxOf(headers, "brake");
        int gearIdx = idxOf(headers, "gear");
        int latIdx = idxOf(headers, "lat", "latitude", "gps_lat");
        int lonIdx = idxOf(headers, "lon", "longitude", "gps_lon");

        if (speedIdx < 0 || throttleIdx < 0 || brakeIdx < 0 || gearIdx < 0) {
            report.addError("Required channels not found in fallback parser.");
            return new SessionData("session-empty", sourceFile, Instant.now(), List.of());
        }

        List<TelemetryPoint> points = new ArrayList<>();
        int skipped = 0;
        for (int i = 1; i < lines.length; i++) {
            String[] row = lines[i].split(delimiter);
            try {
                double timestamp = parseOrDefault(row, tsIdx, i - 1);
                double speed = Double.parseDouble(row[speedIdx]);
                double throttle = Double.parseDouble(row[throttleIdx]);
                double brake = Double.parseDouble(row[brakeIdx]);
                int gear = (int) Double.parseDouble(row[gearIdx]);
                Double latitude = parseOptional(row, latIdx);
                Double longitude = parseOptional(row, lonIdx);
                points.add(new TelemetryPoint(timestamp, speed, throttle, brake, gear, latitude, longitude, null));
            } catch (Exception ex) {
                skipped++;
            }
        }

        if (skipped > 0) {
            report.addWarning("Skipped " + skipped + " malformed fallback rows.");
        }

        return new SessionData(
                "session-" + Math.abs(sourceFile.hashCode()) + "-" + System.currentTimeMillis(),
                sourceFile,
                Instant.now(),
                List.of(new LapData("lap-1", points))
        );
    }

    private int idxOf(String[] headers, String... keys) {
        List<String> normalized = Arrays.stream(headers)
                .map(h -> h == null ? "" : h.trim().toLowerCase(Locale.ROOT))
                .toList();
        for (String key : keys) {
            int exact = normalized.indexOf(key.toLowerCase(Locale.ROOT));
            if (exact >= 0) {
                return exact;
            }
        }
        for (int i = 0; i < headers.length; i++) {
            for (String key : keys) {
                if (normalized.get(i).contains(key.toLowerCase(Locale.ROOT))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static double parseOrDefault(String[] row, int index, double defaultValue) {
        if (index < 0 || index >= row.length) {
            return defaultValue;
        }
        return Double.parseDouble(row[index]);
    }

    private static Double parseOptional(String[] row, int index) {
        if (index < 0 || index >= row.length) {
            return null;
        }
        String value = row[index];
        if (value == null || value.isBlank()) {
            return null;
        }
        return Double.parseDouble(value);
    }
}
