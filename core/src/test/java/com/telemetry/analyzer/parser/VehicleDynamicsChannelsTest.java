package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lateral / longitudinal acceleration, steering angle and engine rpm: column detection and unit
 * handling in the MoTeC and generic CSV paths.
 */
class VehicleDynamicsChannelsTest {

    private static final int ROWS = 200;

    /** Lateral acceleration of sample i in g: a sine of ±1.8 g. */
    private static double latG(int i) {
        return 1.8 * Math.sin(i * 0.1);
    }

    /** Longitudinal acceleration of sample i in g: between -2.4 g (braking) and +0.6 g. */
    private static double lonG(int i) {
        return i % 50 < 10 ? -2.4 : 0.6;
    }

    /** ACC-style MoTeC export; acceleration values written as {@code factor} times the g value. */
    private static String motecCsv(String accelUnit, double factor) {
        return motecCsv(accelUnit, factor, 0);
    }

    /** As above; with {@code lonGapEvery > 0} the G_LON cell of every such row is left empty. */
    private static String motecCsv(String accelUnit, double factor, int lonGapEvery) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"Format\",\"MoTeC CSV File\"\n\"Venue\",\"monza\"\n\n\n");
        sb.append("\"Time\",\"Distance\",\"G_LAT\",\"ROTY\",\"STEERANGLE\",\"SPEED\",\"THROTTLE\",\"BRAKE\",\"GEAR\","
                + "\"G_LON\",\"RPMS\"\n");
        sb.append("\"s\",\"m\",\"").append(accelUnit).append("\",\"deg/s\",\"deg\",\"km/h\",\"%\",\"%\",\"no\",\"")
                .append(accelUnit).append("\",\"1/min\"\n\n\n");
        for (int i = 0; i < ROWS; i++) {
            String lon = lonGapEvery > 0 && i % lonGapEvery == 0 ? ""
                    : String.format(Locale.ROOT, "%.2f", lonG(i) * factor);
            sb.append(String.format(Locale.ROOT,
                    "\"%.3f\",\"%d\",\"%.2f\",\"1.0\",\"%d\",\"200.0\",\"100\",\"0\",\"5\",\"%s\",\"%d\"%n",
                    i * 0.005, i, latG(i) * factor, i - 100, lon, 7000 + i));
        }
        return sb.toString();
    }

    private static List<TelemetryPoint> points(SessionData session) {
        return session.laps().get(0).points();
    }

    private static SessionData parse(CharSequence csv, ImportReport report) {
        return new CsvTelemetryParser().parse(csv.toString().getBytes(StandardCharsets.UTF_8),
                report.getSourceFile(), report);
    }

    @Test
    void motecImportsTheNewChannels() {
        ImportReport report = new ImportReport("m.csv");
        SessionData session = AdaptedCsvProfiles.parseMoTeCWorkbook(motecCsv("g", 1), "m.csv", report);

        TelemetryPoint p = points(session).get(20);
        assertEquals(latG(20), p.lateralAcceleration(), 0.005);
        assertEquals(lonG(20), p.longitudinalAcceleration(), 1e-9);
        assertEquals(-80, p.steeringAngle(), 1e-9);
        assertEquals(7020, p.engineRpm(), 1e-9);
        // ROTY stays the yaw rate; G_LAT / G_LON are not taken as GPS coordinates.
        assertEquals(1.0, p.yawRate(), 1e-9);
        assertNull(p.latitude());
        assertNull(p.longitude());
        assertTrue(report.getWarnings().stream().noneMatch(w -> w.contains("G_LAT")), report.getWarnings().toString());
    }

    @Test
    void motecValuesLabelledMetresPerSecondSquaredButInGAreKept() {
        ImportReport report = new ImportReport("m.csv");
        SessionData session = AdaptedCsvProfiles.parseMoTeCWorkbook(motecCsv("m/s2", 1), "m.csv", report);

        assertEquals(latG(20), points(session).get(20).lateralAcceleration(), 0.005);
        assertEquals(-2.4, points(session).get(0).longitudinalAcceleration(), 1e-9);
        assertTrue(report.getWarnings().contains("MoTeC CSV: column \"G_LAT\" is labelled m/s2 but its values are in g "
                + "(99th percentile of |value| = 1.80, below 6); imported as g."), report.getWarnings().toString());
        assertTrue(report.getWarnings().contains("MoTeC CSV: column \"G_LON\" is labelled m/s2 but its values are in g "
                + "(99th percentile of |value| = 2.40, below 6); imported as g."), report.getWarnings().toString());
    }

    @Test
    void motecGenuineMetresPerSecondSquaredAreConvertedToG() {
        ImportReport report = new ImportReport("m.csv");
        SessionData session = AdaptedCsvProfiles.parseMoTeCWorkbook(
                motecCsv("m/s²", ChannelUnits.STANDARD_GRAVITY), "m.csv", report);

        // 1.8 g written as 17.65 m/s² (two decimals) comes back as 1.8 g.
        assertEquals(latG(20), points(session).get(20).lateralAcceleration(), 0.001);
        assertEquals(-2.4, points(session).get(0).longitudinalAcceleration(), 0.001);
        assertEquals(0.6, points(session).get(10).longitudinalAcceleration(), 0.001);
        // Other channels are untouched by the conversion.
        assertEquals(7010, points(session).get(10).engineRpm(), 1e-9);
        assertEquals(10.0, points(session).get(10).distanceMeters(), 1e-9);
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.startsWith("MoTeC CSV: column \"G_LAT\" converted "
                + "from m/s2 to g")), report.getWarnings().toString());
    }

    @Test
    void genericCsvAliasesNextToGpsColumns() {
        // No "time" header, so this goes through the generic (Apache Commons CSV) path.
        StringBuilder csv = new StringBuilder("t;speed;throttle;brake;gear;lat;lon;lat_accuracy;lateral_g;"
                + "accel_long;steer;rpm\n");
        for (int i = 0; i < ROWS; i++) {
            csv.append(String.format(Locale.ROOT, "%.2f;150;100;0;4;46.25;20.14;3.5;%.3f;%.1f;%d;%d%n",
                    i * 0.1, latG(i), lonG(i), 12 - i, 6000 + i));
        }
        csv.append("20.0;150;100;0;4;46.25;20.14;3.5;n/a;0.1;-;\n");

        ImportReport report = new ImportReport("generic.csv");
        SessionData session = new CsvTelemetryParser().parse(csv.toString().getBytes(StandardCharsets.UTF_8),
                "generic.csv", report);

        List<TelemetryPoint> points = points(session);
        assertEquals(ROWS + 1, points.size(), report.getWarnings().toString());
        TelemetryPoint p = points.get(5);
        assertEquals(46.25, p.latitude(), 1e-9);
        assertEquals(20.14, p.longitude(), 1e-9);
        assertEquals(latG(5), p.lateralAcceleration(), 0.001);
        assertEquals(lonG(5), p.longitudinalAcceleration(), 1e-9);
        assertEquals(7, p.steeringAngle(), 1e-9);
        assertEquals(6005, p.engineRpm(), 1e-9);
        // Malformed optional cells become null; the row itself is kept.
        TelemetryPoint last = points.getLast();
        assertNull(last.lateralAcceleration());
        assertNull(last.steeringAngle());
        assertNull(last.engineRpm());
        assertEquals(0.1, last.longitudinalAcceleration(), 1e-9);
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.startsWith("CSV: column \"lateral_g\" has no "
                + "acceleration unit; read as g")), report.getWarnings().toString());
    }

    @Test
    void headerUnitSuffixesAreHonoured() {
        // "timestamp" makes this a channel-row CSV (MoTeC path) without a unit row.
        StringBuilder csv = new StringBuilder("timestamp,speed,throttle,brake,gear,Lat Acc [m/s2],"
                + "Long Acc (m/s^2),Steering Wheel Angle (rad),Engine RPM\n");
        for (int i = 0; i < ROWS; i++) {
            csv.append(String.format(Locale.ROOT, "%.2f,150,100,0,4,%.3f,%.3f,%.4f,%d%n",
                    i * 0.1, latG(i) * ChannelUnits.STANDARD_GRAVITY, lonG(i) * ChannelUnits.STANDARD_GRAVITY,
                    Math.toRadians(90), 6500));
        }

        ImportReport report = new ImportReport("units.csv");
        SessionData session = new CsvTelemetryParser().parse(csv.toString().getBytes(StandardCharsets.UTF_8),
                "units.csv", report);

        TelemetryPoint p = points(session).get(5);
        assertEquals(latG(5), p.lateralAcceleration(), 0.001);
        assertEquals(lonG(5), p.longitudinalAcceleration(), 0.001);
        assertEquals(90, p.steeringAngle(), 0.01);
        assertEquals(6500, p.engineRpm(), 1e-9);
    }

    @Test
    void unlabelledMetresPerSecondSquaredAreDetectedByMagnitude() {
        StringBuilder csv = new StringBuilder("t,speed,throttle,brake,gear,g_lat,g_lon\n");
        for (int i = 0; i < ROWS; i++) {
            csv.append(String.format(Locale.ROOT, "%.2f,150,100,0,4,%.3f,%.3f%n",
                    i * 0.1, latG(i) * ChannelUnits.STANDARD_GRAVITY, lonG(i) * ChannelUnits.STANDARD_GRAVITY));
        }
        ImportReport report = new ImportReport("ms2.csv");
        SessionData session = new CsvTelemetryParser().parse(csv.toString().getBytes(StandardCharsets.UTF_8),
                "ms2.csv", report);

        assertEquals(latG(5), points(session).get(5).lateralAcceleration(), 0.001);
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.startsWith("CSV: column \"g_lat\" has no "
                + "acceleration unit; read as m/s2 and converted to g")), report.getWarnings().toString());
    }

    @Test
    void metresPerSecondSquaredColumnWithoutPartnerAndWithGapsIsConverted() {
        // Regression: converting a column used to unbox the missing values (no longitudinal column,
        // empty cells) and reject the whole import. "Time" selects the channel-row (MoTeC) path,
        // "t" the generic one.
        for (String time : List.of("Time", "t")) {
            StringBuilder csv = new StringBuilder(time + ",speed,throttle,brake,gear,Lateral Acceleration (m/s2)\n");
            for (int i = 0; i < ROWS; i++) {
                String lat = i % 7 == 3 ? "" : String.format(Locale.ROOT, "%.3f", latG(i) * ChannelUnits.STANDARD_GRAVITY);
                csv.append(String.format(Locale.ROOT, "%.2f,150,100,0,4,%s%n", i * 0.1, lat));
            }

            ImportReport report = new ImportReport("lat.csv");
            List<TelemetryPoint> points = points(parse(csv, report));

            assertTrue(report.getErrors().isEmpty(), time + ": " + report.getErrors());
            assertEquals(ROWS, points.size(), time);
            assertEquals(latG(5), points.get(5).lateralAcceleration(), 0.001, time);
            assertNull(points.get(3).lateralAcceleration(), time);
            assertNull(points.get(5).longitudinalAcceleration(), time);
            assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains(
                    "column \"Lateral Acceleration (m/s2)\" converted from m/s2 to g")), report.getWarnings().toString());
        }
    }

    @Test
    void motecGenuineMetresPerSecondSquaredWithEmptyCellsIsConverted() {
        ImportReport report = new ImportReport("m.csv");
        SessionData session = AdaptedCsvProfiles.parseMoTeCWorkbook(
                motecCsv("m/s2", ChannelUnits.STANDARD_GRAVITY, 9), "m.csv", report);

        assertTrue(report.getErrors().isEmpty(), report.getErrors().toString());
        assertEquals(ROWS, points(session).size());
        assertNull(points(session).get(9).longitudinalAcceleration());
        assertEquals(0.6, points(session).get(10).longitudinalAcceleration(), 0.001);
        assertEquals(latG(9), points(session).get(9).lateralAcceleration(), 0.001);
    }

    @Test
    void bothAccelerationColumnsOfAFileGetTheSameUnit() {
        // A gentle longitudinal channel (99th percentile 5.4 m/s², below the limit on its own) next
        // to a clearly m/s² lateral one: both are converted.
        StringBuilder csv = new StringBuilder("t,speed,throttle,brake,gear,Lat Acc [m/s2],Long Acc [m/s2]\n");
        for (int i = 0; i < ROWS; i++) {
            csv.append(String.format(Locale.ROOT, "%.2f,150,100,0,4,%.3f,%.1f%n",
                    i * 0.1, 11 * Math.sin(i * 0.1), i % 50 < 10 ? -5.4 : 1.5));
        }

        ImportReport report = new ImportReport("road.csv");
        List<TelemetryPoint> points = points(parse(csv, report));

        assertEquals(-5.4 / ChannelUnits.STANDARD_GRAVITY, points.get(0).longitudinalAcceleration(), 1e-9);
        assertEquals(11 * Math.sin(1.5) / ChannelUnits.STANDARD_GRAVITY, points.get(15).lateralAcceleration(), 0.001);
        assertTrue(report.getWarnings().contains("CSV: column \"Long Acc [m/s2]\" converted from m/s2 to g "
                + "(99th percentile of |value| = 5.40; decided together with column \"Lat Acc [m/s2]\" of the same "
                + "file, 99th percentile 11.00)."), report.getWarnings().toString());
    }

    @Test
    void columnsWithAnUnrecognisedUnitAreNotImported() {
        StringBuilder csv = new StringBuilder("t,speed,throttle,brake,gear,Lat Acc (ft/s2),Steering (%),rpm\n");
        for (int i = 0; i < ROWS; i++) {
            csv.append(String.format(Locale.ROOT, "%.2f,150,100,0,4,%.2f,%d,6000%n",
                    i * 0.1, 48 * Math.sin(i * 0.1), i % 100 - 50));
        }

        ImportReport report = new ImportReport("ft.csv");
        List<TelemetryPoint> points = points(parse(csv, report));

        assertEquals(ROWS, points.size());
        assertNull(points.get(15).lateralAcceleration());
        assertNull(points.get(15).steeringAngle());
        assertEquals(6000, points.get(15).engineRpm(), 1e-9);
        assertTrue(report.getWarnings().contains("CSV: column \"Lat Acc (ft/s2)\" has an unrecognised acceleration "
                + "unit \"ft/s2\"; channel not imported."), report.getWarnings().toString());
        assertTrue(report.getWarnings().contains("CSV: column \"Steering (%)\" has an unrecognised steering unit "
                + "\"%\"; channel not imported."), report.getWarnings().toString());
    }

    @Test
    void unlabelledSteeringInRadiansIsConvertedToDegrees() {
        // iRacing logs "SteeringWheelAngle" in radians, with no unit in a CSV export.
        StringBuilder csv = new StringBuilder("t,speed,throttle,brake,gear,SteeringWheelAngle\n");
        for (int i = 0; i < ROWS; i++) {
            csv.append(String.format(Locale.ROOT, "%.2f,150,100,0,4,%.4f%n", i * 0.1, 1.5 * Math.sin(i * 0.1)));
        }

        ImportReport report = new ImportReport("ir.csv");
        List<TelemetryPoint> points = points(parse(csv, report));

        assertEquals(Math.toDegrees(1.5 * Math.sin(1.5)), points.get(15).steeringAngle(), 0.01);
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.startsWith("CSV: column \"SteeringWheelAngle\" has "
                + "no unit and its values are small for degrees (99th percentile of |value| = 1.50, below 6.28); read "
                + "as radians")), report.getWarnings().toString());
    }

    @Test
    void nonFiniteCellsAreMissingValues() {
        String csv = """
                t,speed,throttle,brake,gear,g_lat,steer,rpm
                0.0,150,100,0,4,NaN,Infinity,-Infinity
                0.1,150,100,0,4,1.2,-90,6000
                """;
        ImportReport report = new ImportReport("nan.csv");
        List<TelemetryPoint> points = points(parse(csv, report));

        assertEquals(2, points.size());
        assertNull(points.get(0).lateralAcceleration());
        assertNull(points.get(0).steeringAngle());
        assertNull(points.get(0).engineRpm());
        assertEquals(6000, points.get(1).engineRpm(), 1e-9);
    }

    @Test
    void resolverPrefersSpecificNamesAndSkipsDerivedChannels() {
        assertEquals("SteeringWheelAngle",
                TelemetryChannelResolver.findSteeringAngle(headers("Steering", "SteeringWheelAngle")));
        assertEquals("steering_angle", TelemetryChannelResolver.findSteeringAngle(headers("steer", "steering_angle")));
        assertEquals("SteeringWheelAngle",
                TelemetryChannelResolver.findSteeringAngle(headers("SteeringWheelAngleMax", "SteeringWheelAngle")));
        for (String derived : List.of("Steering Angle Rate", "steering_angle_velocity", "SteeringWheelAngleMax",
                "Steering Angle Target")) {
            assertNull(TelemetryChannelResolver.findSteeringAngle(headers(derived)), derived);
        }
        assertNull(TelemetryChannelResolver.findEngineRpm(headers("Engine Speed Limit")));
        assertEquals("Engine Speed (rpm)",
                TelemetryChannelResolver.findEngineRpm(headers("Engine Speed Limit", "Engine Speed (rpm)")));
        // AiM GPS-derived accelerations are accelerations, never coordinates.
        assertEquals("GPS LatAcc", TelemetryChannelResolver.findLateralAcceleration(headers("GPS LatAcc")));
        assertEquals("GPS LonAcc", TelemetryChannelResolver.findLongitudinalAcceleration(headers("GPS LonAcc")));
        assertNull(TelemetryChannelResolver.findLat(headers("GPS LatAcc", "GPS Lateral G", "gps_lat_accuracy")));
        assertNull(TelemetryChannelResolver.findLon(headers("GPS LonAcc", "GPS Longitudinal G")));
        assertEquals("GPS Latitude", TelemetryChannelResolver.findLat(headers("GPS LatAcc", "GPS Latitude")));
    }

    @Test
    void openF1CarDataImportsEngineRpm() {
        String csv = """
                brake,date,driver_number,drs,meeting_key,n_gear,rpm,session_key,speed,throttle
                0,2023-09-16T13:08:35.170000+00:00,55,12,1219,7,11141,9159,315,99
                100,2023-09-16T13:08:35.410000+00:00,55,12,1219,6,10950,9159,298,0
                """;
        ImportReport report = new ImportReport("car_data.csv");
        SessionData session = new CsvTelemetryParser().parse(csv.getBytes(StandardCharsets.UTF_8), "car_data.csv", report);

        assertTrue(report.getErrors().isEmpty(), report.getErrors().toString());
        assertEquals(2, points(session).size());
        assertEquals(11141, points(session).get(0).engineRpm(), 1e-9);
        assertEquals(6, points(session).get(1).gear());
        assertNull(points(session).get(0).lateralAcceleration());
    }

    @Test
    void resolverDoesNotConfuseGpsAndAccelerationColumns() {
        for (String gps : List.of("lat", "Latitude", "GPS_Lat", "gps lat", "lat_accuracy", "avg_lat", "lat_gps")) {
            assertNull(TelemetryChannelResolver.findLateralAcceleration(headers(gps)), gps);
        }
        for (String gps : List.of("lon", "long", "Longitude", "GPS_Long", "lon_accuracy", "long_acc_estimate_quality")) {
            assertNull(TelemetryChannelResolver.findLongitudinalAcceleration(headers(gps)), gps);
        }
        for (String acc : List.of("G_LAT", "g lat", "Lateral G", "lat-acc (g)", "Accel.Lat [m/s2]",
                "LateralAcceleration", "LatAccel", "vehicle_lateral_acceleration")) {
            assertEquals(acc, TelemetryChannelResolver.findLateralAcceleration(headers(acc)), acc);
        }
        for (String acc : List.of("G_LON", "G_LONG", "long_acc", "Longitudinal G", "accel_long", "Lon Acc (m/s²)",
                "LongAccel")) {
            assertEquals(acc, TelemetryChannelResolver.findLongitudinalAcceleration(headers(acc)), acc);
        }
        assertEquals("SteeringWheelAngle", TelemetryChannelResolver.findSteeringAngle(headers("SteeringWheelAngle")));
        assertEquals("EngineRPM", TelemetryChannelResolver.findEngineRpm(headers("EngineRPM")));
        assertNull(TelemetryChannelResolver.findLat(headers("G_LAT", "lat_acc")));
        assertNull(TelemetryChannelResolver.findLon(headers("G_LON", "lon_acc")));
        assertNull(TelemetryChannelResolver.findSteeringAngle(headers("steering_torque", "steer_rate")));
        assertEquals("STEERANGLE", TelemetryChannelResolver.findSteeringAngle(headers("STEER_RATE", "STEERANGLE")));
        assertNull(TelemetryChannelResolver.findEngineRpm(headers("WHEEL_RPM_LF", "rpm_limit")));
        assertEquals("Engine Speed (rpm)", TelemetryChannelResolver.findEngineRpm(headers("Engine Speed (rpm)")));
        assertEquals("m/s2", TelemetryChannelResolver.unitSuffix("Lat Acc [m/s2]"));
        assertNull(TelemetryChannelResolver.unitSuffix("[G_LAT]"));
    }

    @Test
    void accelerationLabelsAndPercentile() {
        assertEquals(ChannelUnits.AccelerationLabel.G, ChannelUnits.accelerationLabel("G"));
        assertEquals(ChannelUnits.AccelerationLabel.METRES_PER_SECOND_SQUARED, ChannelUnits.accelerationLabel("m/s2"));
        assertEquals(ChannelUnits.AccelerationLabel.METRES_PER_SECOND_SQUARED, ChannelUnits.accelerationLabel("m/s²"));
        assertEquals(ChannelUnits.AccelerationLabel.METRES_PER_SECOND_SQUARED, ChannelUnits.accelerationLabel("m/s^2"));
        assertEquals(ChannelUnits.AccelerationLabel.NONE, ChannelUnits.accelerationLabel(".."));
        assertEquals(ChannelUnits.AccelerationLabel.NONE, ChannelUnits.accelerationLabel(null));
        assertEquals(ChannelUnits.AccelerationLabel.UNRECOGNISED, ChannelUnits.accelerationLabel("ft/s2"));
        assertEquals(ChannelUnits.AccelerationLabel.UNRECOGNISED, ChannelUnits.accelerationLabel("mG"));
        assertEquals(ChannelUnits.SteeringLabel.DEGREES, ChannelUnits.steeringLabel("deg"));
        assertEquals(ChannelUnits.SteeringLabel.DEGREES, ChannelUnits.steeringLabel("°"));
        assertEquals(ChannelUnits.SteeringLabel.RADIANS, ChannelUnits.steeringLabel("rad"));
        assertEquals(ChannelUnits.SteeringLabel.NONE, ChannelUnits.steeringLabel(".."));
        assertEquals(ChannelUnits.SteeringLabel.UNRECOGNISED, ChannelUnits.steeringLabel("%"));

        // |values| 1..100: the 99th percentile (nearest rank) is the 99th smallest.
        List<TelemetryPoint> pts = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            pts.add(new TelemetryPoint(i, 0, 0, 0, 0, null, null, null, null, i % 2 == 0 ? i : -1.0 * i, null, null, null));
        }
        pts.add(new TelemetryPoint(0, 0, 0, 0, 0, null, null, null));
        assertEquals(99, ChannelUnits.percentileOfMagnitude(pts, TelemetryPoint::lateralAcceleration, 0.99), 1e-12);
        assertTrue(Double.isNaN(ChannelUnits.percentileOfMagnitude(pts, TelemetryPoint::engineRpm, 0.99)));
    }

    private static Map<String, Integer> headers(String... names) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            map.put(names[i], i);
        }
        return map;
    }
}
