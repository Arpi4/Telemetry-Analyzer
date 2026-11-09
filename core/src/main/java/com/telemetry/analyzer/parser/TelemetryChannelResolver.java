package com.telemetry.analyzer.parser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves telemetry column names for generic CSV and MoTeC i2 / export-style headers.
 */
public final class TelemetryChannelResolver {

    /** Trailing "(unit)" or "[unit]" after a non-empty name. */
    private static final Pattern UNIT_SUFFIX = Pattern.compile("(?<=[^\\s(\\[])\\s*[(\\[]([^()\\[\\]]*)[)\\]]\\s*$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s_\\-./]+");
    private static final Pattern CAMEL_CASE_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");

    /** Name parts of channels derived from a quantity rather than the quantity itself. */
    private static final Set<String> DERIVED_QUALIFIERS = Set.of(
            "rate", "velocity", "vel", "max", "min", "limit", "torque", "target", "offset");

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
        return findFuzzyCoordinate(headers, "gps lat", "latitude");
    }

    public static String findLon(Map<String, Integer> headers) {
        String key = findExact(headers, "lon", "longitude", "gps_lon", "gps long", "gps lon", "gps longitude");
        if (key != null) {
            return key;
        }
        return findFuzzyCoordinate(headers, "gps long", "gps lon", "longitude");
    }

    public static String findDistance(Map<String, Integer> headers) {
        String key = findExact(headers, "distance", "dist", "odo", "odometer", "lap dist");
        if (key != null) {
            return key;
        }
        return findFuzzy(headers, "distance", "odometer");
    }

    /** Yaw rate in deg/s, e.g. MoTeC/ACC "ROTY". Exact names only: "yaw" alone is often an angle. */
    public static String findYawRate(Map<String, Integer> headers) {
        String key = findExact(headers, "roty", "yaw rate", "yaw_rate", "yawrate", "gyro yaw rate");
        if (key != null) {
            return key;
        }
        return findFuzzy(headers, "yaw rate", "yaw_rate");
    }

    /*
     * The optional vehicle-dynamics channels below are matched on normalised names (see
     * normalizeName): "G_LAT", "g lat", "G-Lat [m/s2]" all become "g_lat". Exact names are tried in
     * the order listed, so a specific name ("steering_angle") wins over a weaker one ("steer")
     * wherever they stand in the file. Short forms such as "lat_acc" or "lon_g" are matched exactly
     * only, because as fragments they also occur in GPS columns ("lat_accuracy", "lon_gps"); bare
     * "lat" / "lon" / "long" are never accepted. Fragment matches skip derived channels such as
     * "Steering Angle Rate" or "Engine Speed Limit" (see DERIVED_QUALIFIERS).
     */

    /** Lateral acceleration, e.g. MoTeC/ACC "G_LAT", AiM "GPS LatAcc". */
    public static String findLateralAcceleration(Map<String, Integer> headers) {
        String key = findExactNormalized(headers,
                "g_lat", "glat", "lat_g", "g_lateral", "lateral_g", "lateral_g_force", "gforce_lat", "g_force_lat",
                "lat_acc", "lat_accel", "lat_acceleration", "acc_lat", "accel_lat", "acceleration_lat",
                "lateral_acc", "lateral_accel", "lateral_acceleration", "accel_lateral", "acceleration_lateral",
                "gps_lat_acc", "gps_lat_accel");
        if (key != null) {
            return key;
        }
        return findFuzzyNormalized(headers, "lateral_acc", "accel_lat");
    }

    /** Longitudinal acceleration, e.g. MoTeC/ACC "G_LON", AiM "GPS LonAcc". */
    public static String findLongitudinalAcceleration(Map<String, Integer> headers) {
        String key = findExactNormalized(headers,
                "g_lon", "g_long", "glon", "glong", "lon_g", "long_g", "g_longitudinal", "longitudinal_g",
                "longitudinal_g_force", "gforce_lon", "gforce_long", "g_force_lon", "g_force_long",
                "lon_acc", "long_acc", "lon_accel", "long_accel", "lon_acceleration", "long_acceleration",
                "acc_lon", "acc_long", "accel_lon", "accel_long", "acceleration_lon", "acceleration_long",
                "longitudinal_acc", "longitudinal_accel", "longitudinal_acceleration",
                "accel_longitudinal", "acceleration_longitudinal",
                "gps_lon_acc", "gps_long_acc", "gps_lon_accel", "gps_long_accel");
        if (key != null) {
            return key;
        }
        return findFuzzyNormalized(headers, "longitudinal_acc", "accel_long");
    }

    /** Steering wheel angle, e.g. MoTeC/ACC "STEERANGLE". */
    public static String findSteeringAngle(Map<String, Integer> headers) {
        String key = findExactNormalized(headers,
                "steerangle", "steer_angle", "steering_angle", "steeringangle", "steering_wheel_angle",
                "steeringwheelangle", "steer_ang", "steering", "steer");
        if (key != null) {
            return key;
        }
        return findFuzzyNormalized(headers, "steering_wheel_angle", "steering_angle", "steer_angle", "steerangle");
    }

    /** Engine speed, e.g. MoTeC/ACC "RPMS", OpenF1 car_data "rpm". Wheel rpm columns are not matched. */
    public static String findEngineRpm(Map<String, Integer> headers) {
        String key = findExactNormalized(headers,
                "rpm", "rpms", "engine_rpm", "enginerpm", "eng_rpm", "engine_speed", "engine_revs", "revs");
        if (key != null) {
            return key;
        }
        return findFuzzyNormalized(headers, "engine_rpm", "engine_speed", "eng_rpm");
    }

    /**
     * Unit written into a header after the name, e.g. "m/s2" for "Lat Acc (m/s2)" or "g" for
     * "G_LAT [g]"; {@code null} when there is none.
     */
    public static String unitSuffix(String header) {
        if (header == null) {
            return null;
        }
        Matcher m = UNIT_SUFFIX.matcher(header);
        if (!m.find()) {
            return null;
        }
        String unit = m.group(1).trim();
        return unit.isEmpty() ? null : unit;
    }

    /**
     * Unit suffix removed, camelCase split ("LatAccel" → "lat_accel"), lower case, runs of
     * blanks / "_" / "-" / "." / "/" collapsed to "_".
     */
    static String normalizeName(String raw) {
        String s = UNIT_SUFFIX.matcher(raw.trim()).replaceFirst("");
        s = CAMEL_CASE_BOUNDARY.matcher(s).replaceAll("$1_$2").toLowerCase(Locale.ROOT);
        s = SEPARATORS.matcher(s).replaceAll("_");
        int from = 0;
        int to = s.length();
        while (from < to && s.charAt(from) == '_') {
            from++;
        }
        while (to > from && s.charAt(to - 1) == '_') {
            to--;
        }
        return s.substring(from, to);
    }

    /**
     * True for names of accelerations or GPS accuracies ("g_lat", "gps_lat_acc", "lateral_g",
     * "lat_accuracy"), which must not be read as coordinates. Takes a normalised name.
     */
    static boolean isAccelerationOrAccuracy(String normalizedName) {
        String n = "_" + normalizedName + "_";
        return n.contains("acc") || n.contains("lateral") || n.contains("longitudinal") || n.contains("_g_");
    }

    /** First header whose normalised name equals a candidate; candidates are tried in order. */
    private static String findExactNormalized(Map<String, Integer> headers, String... candidatesOrdered) {
        Map<String, String> byNormalizedName = new LinkedHashMap<>();
        for (String raw : headers.keySet()) {
            byNormalizedName.putIfAbsent(normalizeName(raw), raw);
        }
        for (String c : candidatesOrdered) {
            String raw = byNormalizedName.get(c);
            if (raw != null) {
                return raw;
            }
        }
        return null;
    }

    private static String findFuzzyNormalized(Map<String, Integer> headers, String... fragmentsOrdered) {
        for (String fragment : fragmentsOrdered) {
            for (String raw : headers.keySet()) {
                String norm = normalizeName(raw);
                if (norm.contains(fragment) && !isDerivedChannel(norm)) {
                    return raw;
                }
            }
        }
        return null;
    }

    /** A rate, limit or other quantity derived from a channel, e.g. "steering_angle_rate". */
    private static boolean isDerivedChannel(String normalizedName) {
        for (String token : normalizedName.split("_")) {
            if (DERIVED_QUALIFIERS.contains(token)) {
                return true;
            }
        }
        return false;
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

    /** Like {@link #findFuzzy}, but never an acceleration such as "GPS LatAcc" or a GPS accuracy. */
    private static String findFuzzyCoordinate(Map<String, Integer> headers, String... fragmentsOrdered) {
        for (String fragment : fragmentsOrdered) {
            for (String raw : headers.keySet()) {
                if (raw.trim().toLowerCase(Locale.ROOT).contains(fragment)
                        && !isAccelerationOrAccuracy(normalizeName(raw))) {
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