package com.telemetry.analyzer.parser;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves telemetry column names for generic CSV and MoTeC i2 / export-style headers.
 */
public final class TelemetryChannelResolver {

    private TelemetryChannelResolver() {
    }

    public static String findTime(Map<String, Integer> headers) {
        String key = findExact(headers, "timestamp", "time", "t", "laptime", "lap time");
        if (key != null) {
            return key;
        }
        return findFuzzy(headers, "elapsed", "lap time", "time");
    }

    public static String findSpeed(Map<String, Integer> headers) {
        String key = findExact(headers, "speed", "velocity", "kmh", "gps speed", "wheel speed");
        if (key != null) {
            return key;
        }
        return findFuzzy(headers, "gps speed", "wheel speed", "vehicle speed", "ground speed", "speed");
    }

    public static String findThrottle(Map<String, Integer> headers) {
        String key = findExact(headers, "throttle", "gas", "tps", "throttle pos", "throttle position");
        if (key != null) {
            return key;
        }
        return findFuzzy(headers, "throttle pos", "throttle position", "accel pedal", "accelerator", "throttle", "tps");
    }

    public static String findBrake(Map<String, Integer> headers) {
        String key = findExact(headers, "brake", "brk", "brake pos", "brake position", "brake pressure");
        if (key != null) {
            return key;
        }
        return findFuzzy(headers, "brake pos", "brake position", "brake pressure", "brake pedal", "brake");
    }

    public static String findGear(Map<String, Integer> headers) {
        String key = findExact(headers, "gear", "gear pos", "gear position", "gear (manual)");
        if (key != null) {
            return key;
        }
        return findFuzzy(headers, "gear position", "gear pos", "gear");
    }

    public static String findLat(Map<String, Integer> headers) {
        String key = findExact(headers, "lat", "latitude", "gps_lat", "gps lat", "gps latitude");
        if (key != null) {
            return key;
        }
        return findFuzzy(headers, "gps lat", "latitude");
    }

    public static String findLon(Map<String, Integer> headers) {
        String key = findExact(headers, "lon", "longitude", "gps_lon", "gps long", "gps lon", "gps longitude");
        if (key != null) {
            return key;
        }
        return findFuzzy(headers, "gps long", "gps lon", "longitude");
    }

    private static String findExact(Map<String, Integer> headers, String... candidates) {
        for (String raw : headers.keySet()) {
            String norm = raw.trim().toLowerCase(Locale.ROOT);
            for (String c : candidates) {
                if (norm.equals(c.toLowerCase(Locale.ROOT))) {
                    return raw;
                }
            }
        }
        return null;
    }

    /**
     * Prefer longer / more specific fragments first (e.g. "gps speed" before "speed").
     */
    private static String findFuzzy(Map<String, Integer> headers, String... fragmentsOrdered) {
        for (String fragment : fragmentsOrdered) {
            String f = fragment.toLowerCase(Locale.ROOT);
            for (String raw : headers.keySet()) {
                String norm = raw.trim().toLowerCase(Locale.ROOT);
                if (norm.contains(f)) {
                    return raw;
                }
            }
        }
        return null;
    }
}
