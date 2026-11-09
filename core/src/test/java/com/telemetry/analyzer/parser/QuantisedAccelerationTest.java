package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quantised acceleration columns: detection, the longitudinal acceleration derived from speed that
 * replaces a quantised G_LON, and the quantised G_LAT that is kept.
 */
class QuantisedAccelerationTest {

    /** 2 s at 200 Hz. */
    private static final int ROWS = 401;

    private static final double DT = 0.005;

    /**
     * Constant deceleration: v(t) = 180 km/h − 36 km/h/s · t, i.e. 50 m/s − 10 m/s² · t, so
     * dv/dt = −10 m/s² and a = −10 / 9.80665 = −1.0197 g, −1.020 after rounding to 0.001 g.
     */
    private static final double DECELERATION_G = -1.020;

    private static double speedKmh(int i) {
        return 180 - 36 * i * DT;
    }

    /** ACC-style MoTeC export with a beacon at 1.0 s (two laps); G_LAT and G_LON cells per row. */
    private static String motecCsv(IntFunction<String> lat, IntFunction<String> lon) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"Format\",\"MoTeC CSV File\"\n\"Beacon Markers\",\"1.000 \"\n\n\n");
        sb.append("\"Time\",\"Distance\",\"G_LAT\",\"SPEED\",\"THROTTLE\",\"BRAKE\",\"GEAR\",\"G_LON\"\n");
        sb.append("\"s\",\"m\",\"m/s2\",\"km/h\",\"%\",\"%\",\"no\",\"m/s2\"\n\n\n");
        double s = 0;
        for (int i = 0; i < ROWS; i++) {
            sb.append(String.format(Locale.ROOT, "\"%.3f\",\"%.3f\",\"%s\",\"%.3f\",\"0\",\"80\",\"3\",\"%s\"%n",
                    i * DT, s, lat.apply(i), speedKmh(i), lon.apply(i)));
            s += speedKmh(i) / 3.6 * DT;
        }
        return sb.toString();
    }

    private static List<TelemetryPoint> allPoints(SessionData session) {
        List<TelemetryPoint> out = new ArrayList<>();
        session.laps().forEach(lap -> out.addAll(lap.points()));
        return out;
    }

    @Test
    void quantisedLongitudinalIsReplacedByTheSpeedDerivative() {
        ImportReport report = new ImportReport("acc.csv");
        SessionData session = AdaptedCsvProfiles.parseMoTeCWorkbook(motecCsv(
                i -> String.format(Locale.ROOT, "%.2f", 0.8 * Math.sin(i * 0.05)),
                i -> i % 3 == 0 ? "-2" : "-1"), "acc.csv", report);

        assertEquals(2, session.laps().size());
        // Every sample, including the one-sided windows at the first and last sample and the sample
        // shared by the two laps, gets the hand-computed value.
        for (TelemetryPoint p : allPoints(session)) {
            assertEquals(DECELERATION_G, p.longitudinalAcceleration(), 1e-9, "t = " + p.timestamp());
        }
        LapData lap1 = session.laps().get(0);
        LapData lap2 = session.laps().get(1);
        assertEquals(1.0, lap2.points().getFirst().timestamp(), 1e-9);
        assertEquals(lap1.points().getLast().longitudinalAcceleration(), lap2.points().getFirst().longitudinalAcceleration());
        // The lateral column is not quantised and stays as written.
        assertEquals(0.8 * Math.sin(10 * 0.05), lap1.points().get(10).lateralAcceleration(), 0.005);

        assertTrue(report.getWarnings().contains("MoTeC CSV: column \"G_LON\" holds whole numbers only (2 distinct "
                + "values: -2, -1), a resolution of 1 g; replaced by longitudinal acceleration derived from \"SPEED\" "
                + "(dv/dt as a central difference over t +/- 0.25 s, divided by 9.80665 m/s2)."),
                report.getWarnings().toString());
        // The unit warning of the replaced column would say "imported as g" and is left out.
        assertTrue(report.getWarnings().stream().noneMatch(w -> w.contains("\"G_LON\" is labelled")),
                report.getWarnings().toString());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("\"G_LAT\" is labelled m/s2")),
                report.getWarnings().toString());
    }

    @Test
    void quantisedLateralIsKeptWithAWarning() {
        ImportReport report = new ImportReport("acc.csv");
        SessionData session = AdaptedCsvProfiles.parseMoTeCWorkbook(motecCsv(
                i -> i % 40 < 15 ? "1" : i % 40 < 25 ? "0" : "-1",
                i -> String.format(Locale.ROOT, "%.2f", -1.5 + 0.01 * (i % 50))), "acc.csv", report);

        List<TelemetryPoint> points = session.laps().get(0).points();
        assertEquals(1, points.get(3).lateralAcceleration(), 1e-9);
        assertEquals(-1, points.get(30).lateralAcceleration(), 1e-9);
        // A non-quantised G_LON is imported unchanged, not derived from speed.
        assertEquals(-1.5 + 0.01 * 7, points.get(7).longitudinalAcceleration(), 1e-9);
        assertTrue(report.getWarnings().contains("MoTeC CSV: column \"G_LAT\" holds whole numbers only (3 distinct "
                + "values: -1, 0, 1), a resolution of 1 g; kept as is, because lateral acceleration cannot be derived "
                + "from speed alone."), report.getWarnings().toString());
        assertTrue(report.getWarnings().stream().noneMatch(w -> w.contains("\"G_LON\" holds whole numbers")),
                report.getWarnings().toString());
    }

    @Test
    void nonQuantisedLongitudinalIsUnchanged() {
        ImportReport report = new ImportReport("acc.csv");
        SessionData session = AdaptedCsvProfiles.parseMoTeCWorkbook(motecCsv(
                i -> "0.25",
                i -> String.format(Locale.ROOT, "%.2f", 0.5 * Math.cos(i * 0.1))), "acc.csv", report);

        List<TelemetryPoint> points = session.laps().get(0).points();
        for (int i : new int[]{0, 17, 150}) {
            assertEquals(Math.round(50 * Math.cos(i * 0.1)) / 100.0, points.get(i).longitudinalAcceleration(), 1e-9);
        }
        assertTrue(report.getWarnings().stream().noneMatch(w -> w.contains("whole numbers")),
                report.getWarnings().toString());
    }

    @Test
    void detectionNeedsFewWholeLevels() {
        // 199 of 200 whole numbers (99.5 %), two levels: quantised (one interpolated value is tolerated).
        QuantisedAcceleration.Levels levels = QuantisedAcceleration.quantisedLevels(
                column(200, i -> i == 7 ? -0.5 : i % 2 == 0 ? -0.0 : -1), TelemetryPoint::lateralAcceleration);
        assertArrayEquals(new double[]{-1, 0}, levels.values());
        assertEquals(1, levels.otherValues());
        // 196 of 200 whole numbers (98 %): not quantised.
        assertNull(QuantisedAcceleration.quantisedLevels(
                column(200, i -> i % 50 == 7 ? 0.5 : i % 2), TelemetryPoint::lateralAcceleration));
        // Eight levels are quantised, nine evenly used ones (e.g. whole m/s²) are not: the eight
        // most frequent hold 8/9 = 89 % of the values.
        levels = QuantisedAcceleration.quantisedLevels(column(200, i -> (double) (i % 8 - 4)),
                TelemetryPoint::lateralAcceleration);
        assertArrayEquals(new double[]{-4, -3, -2, -1, 0, 1, 2, 3}, levels.values());
        assertEquals(0, levels.otherValues());
        assertNull(QuantisedAcceleration.quantisedLevels(
                column(200, i -> (double) (i % 9 - 4)), TelemetryPoint::lateralAcceleration));
        // Too few values to tell.
        assertNull(QuantisedAcceleration.quantisedLevels(column(19, i -> 0.0), TelemetryPoint::lateralAcceleration));
        // Missing values do not count.
        assertNull(QuantisedAcceleration.quantisedLevels(column(200, i -> Double.NaN),
                TelemetryPoint::lateralAcceleration));
    }

    @Test
    void rareSpikesAreOutliersNotLevels() {
        // ACC-like G_LON, 2000 samples on -2..1 g, plus nine one-sample collision spikes of nine
        // different whole values (13 distinct values in all). A spike holds 0.05 % of the values,
        // not more than 0.1 %, so it is no level; the four levels hold 99.55 %.
        double[] spikes = {-40, -25, -12, -7, -5, 3, 9, 15, 30};
        List<TelemetryPoint> points = column(2000, i -> i % 230 == 3 ? spikes[i / 230] : (double) (i % 4 - 2));
        QuantisedAcceleration.Levels levels = QuantisedAcceleration.quantisedLevels(points,
                TelemetryPoint::lateralAcceleration);
        assertArrayEquals(new double[]{-2, -1, 0, 1}, levels.values());
        assertEquals(9, levels.otherValues());
        // A value held by 3 samples (0.15 %) is a level.
        levels = QuantisedAcceleration.quantisedLevels(column(2000, i -> i % 700 == 5 ? -40.0 : i % 4 - 2),
                TelemetryPoint::lateralAcceleration);
        assertArrayEquals(new double[]{-40, -2, -1, 0, 1}, levels.values());
        assertEquals(0, levels.otherValues());
        // 23 spikes (1.15 %) are too many outliers.
        assertNull(QuantisedAcceleration.quantisedLevels(column(2000, i -> i % 87 == 3 ? 20.0 + i : i % 4 - 2),
                TelemetryPoint::lateralAcceleration));

        // Through the importer: two interpolated values (0.5 % of 401) are counted in the warning,
        // and the column is still replaced.
        ImportReport report = new ImportReport("acc.csv");
        SessionData session = AdaptedCsvProfiles.parseMoTeCWorkbook(motecCsv(
                i -> "0.50",
                i -> i == 100 || i == 300 ? "-1.5" : i % 3 == 0 ? "-2" : "-1"), "acc.csv", report);
        for (TelemetryPoint p : allPoints(session)) {
            assertEquals(DECELERATION_G, p.longitudinalAcceleration(), 1e-9, "t = " + p.timestamp());
        }
        assertTrue(report.getWarnings().contains("MoTeC CSV: column \"G_LON\" holds whole numbers (2 distinct values: "
                + "-2, -1, and 2 samples with other values), a resolution of 1 g; replaced by longitudinal acceleration "
                + "derived from \"SPEED\" (dv/dt as a central difference over t +/- 0.25 s, divided by 9.80665 m/s2)."),
                report.getWarnings().toString());
    }

    /** Sample period of the derivative tests: 1/256 s, exact in binary, so t ± 0.25 s falls on samples. */
    private static final double BINARY_DT = 1.0 / 256;

    @Test
    void derivativeIsCentredOnTheSampleAndHalfASecondWide() {
        // v(t) = g0 · t³ / 3 (m/s) over 0..2 s: true acceleration t² g. The central difference over
        // t ± h gives ((t + h)³ − (t − h)³) / (6h) = t² + h²/3, which pins both the centre and
        // h = 0.25 s (t² + 1/48). A forward difference over [t, t + h] would give t² + t·h + h²/3
        // (0.27 g more at 1 s), h = 0.15 s would give t² + 0.0075.
        double g0 = ChannelUnits.STANDARD_GRAVITY;
        List<TelemetryPoint> points = new ArrayList<>();
        for (int i = 0; i <= 512; i++) {
            double t = i * BINARY_DT;
            points.add(point(t, g0 * t * t * t / 3 * 3.6));
        }

        double[] a = QuantisedAcceleration.longitudinalFromSpeed(points);

        double h = 0.25;
        for (int i = 64; i <= 448; i++) { // full window
            double t = i * BINARY_DT;
            assertEquals(t * t + h * h / 3, a[i], 0.0006, "t = " + t);
        }
        // One-sided at the ends of the recording: forward over [0, h], backward over [2 − h, 2].
        assertEquals(h * h / 3, a[0], 0.0006);
        double end = 2;
        assertEquals((end * end * end - Math.pow(end - h, 3)) / (3 * h), a[512], 0.0006);
        // Near the end the window is cut to the recording: [t − h, 2] at t = 1.875 s.
        double t = 480 * BINARY_DT;
        assertEquals((end * end * end - Math.pow(t - h, 3)) / (3 * (end - t + h)), a[480], 0.0006);
    }

    @Test
    void derivativeAveragesOutAStepHeldSpeed() {
        // Speed written with 0.1 km/h resolution and held for 16 samples (1/16 s), rising one step
        // each time: 1.6 km/h/s. Every 0.5 s window spans exactly 8 steps whatever its phase, so
        // every sample with a full window reads 1.6 / 3.6 / g0 = 0.0453 g. A sample-to-sample
        // difference would read 0 or 0.725 g, a narrower window 0.038 or 0.048 g by phase.
        List<TelemetryPoint> points = new ArrayList<>();
        for (int i = 0; i <= 1024; i++) {
            points.add(point(i * BINARY_DT, 100 + 0.1 * (i / 16)));
        }

        double[] a = QuantisedAcceleration.longitudinalFromSpeed(points);

        for (int i = 64; i <= 960; i++) {
            assertEquals(0.045, a[i], 1e-12, "sample " + i);
        }
    }

    @Test
    void derivativeNeverCrossesGapsOrTimeResets() {
        List<TelemetryPoint> points = new ArrayList<>();
        // Segment A: 0..1 s at 20 Hz, constant 100 km/h -> 0 g.
        for (int i = 0; i <= 20; i++) {
            points.add(point(i * 0.05, 100));
        }
        // 4 s logger pause. Segment B: 5..6 s at 20 Hz, accelerating at 10 m/s² (36 km/h per s)
        // from 50 km/h -> +1.020 g, also at its first sample (forward difference only).
        for (int i = 0; i <= 20; i++) {
            points.add(point(5 + i * 0.05, 50 + 36 * i * 0.05));
        }
        // Another 4 s pause. Segment C: 10..12 s at 2 Hz (slower than the window), braking at
        // 10 m/s² -> the window falls back to the neighbouring samples: -1.020 g.
        for (int i = 0; i <= 4; i++) {
            points.add(point(10 + i * 0.5, 200 - 36 * i * 0.5));
        }
        // Segment D: time goes back; three samples with one timestamp cannot be differentiated.
        points.add(point(7, 100));
        points.add(point(7, 120));
        points.add(point(7, 140));
        // Segment E: a single sample after a 2 s gap.
        points.add(point(9, 80));

        double[] a = QuantisedAcceleration.longitudinalFromSpeed(points);

        for (int i = 0; i <= 20; i++) {
            assertEquals(0, a[i], 1e-12, "A " + i);
            assertEquals(1.020, a[21 + i], 1e-12, "B " + i);
        }
        for (int i = 0; i <= 4; i++) {
            assertEquals(-1.020, a[42 + i], 1e-12, "C " + i);
        }
        for (int i = 47; i < a.length; i++) {
            assertTrue(Double.isNaN(a[i]), "sample " + i + " = " + a[i]);
        }
    }

    @Test
    void underivableSamplesAreImportedAsMissing() {
        // Generic path ("t" is not "time", so no channel-row detection): a 30-sample segment at
        // 10 Hz, then a time reset that leaves one isolated sample at the end. Every long_acc cell
        // is 0, so the column is quantised and replaced.
        StringBuilder csv = new StringBuilder("t,speed,throttle,brake,gear,long_acc\n");
        for (int i = 0; i < 30; i++) {
            csv.append(String.format(Locale.ROOT, "%.2f,%.3f,100,0,4,0%n", i * 0.1,
                    100 + 3.6 * ChannelUnits.STANDARD_GRAVITY * 0.5 * i * 0.1));
        }
        csv.append("1.00,150,100,0,4,0\n");
        ImportReport report = new ImportReport("gen.csv");
        SessionData session = new CsvTelemetryParser().parse(csv.toString().getBytes(StandardCharsets.UTF_8),
                "gen.csv", report);

        List<TelemetryPoint> points = session.laps().get(0).points();
        assertEquals(31, points.size());
        // 0.5 g: speed rises by 0.5 · 9.80665 m/s² (written with 0.001 km/h resolution).
        assertEquals(0.5, points.get(15).longitudinalAcceleration(), 0.002);
        assertNull(points.get(30).longitudinalAcceleration());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.startsWith("CSV: column \"long_acc\" holds whole "
                + "numbers only (1 distinct value: 0), a resolution of 1 g; replaced by longitudinal acceleration "
                + "derived from \"speed\"")), report.getWarnings().toString());
    }

    private static TelemetryPoint point(double t, double kmh) {
        return new TelemetryPoint(t, kmh, 0, 0, 3, null, null, null, null, null, 0.0, null, null);
    }

    private static List<TelemetryPoint> column(int n, IntFunction<Double> value) {
        List<TelemetryPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new TelemetryPoint(i * 0.01, 100, 0, 0, 3, null, null, null, null, value.apply(i), null, null,
                    null));
        }
        return out;
    }
}
