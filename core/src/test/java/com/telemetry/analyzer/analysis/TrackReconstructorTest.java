package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.analysis.TrackReconstructor.Source;
import com.telemetry.analyzer.analysis.TrackReconstructor.TrackOutline;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackReconstructorTest {

    private final TrackReconstructor reconstructor = new TrackReconstructor();

    /** Circle of circumference 1000 m driven at 20 m/s (50 s), sampled at 100 Hz. */
    private static LapData circle(double yawBiasDegPerS) {
        double v = 20;
        double period = 1000 / v;
        double yaw = 360 / period;
        List<TelemetryPoint> pts = new ArrayList<>();
        for (int i = 0; i <= 5000; i++) {
            double t = i * 0.01;
            pts.add(new TelemetryPoint(t, v * 3.6, 100, 0, 4, null, null, v * t, yaw + yawBiasDegPerS));
        }
        return new LapData("lap-1", pts);
    }

    @Test
    void reconstructsACircle() {
        TrackOutline o = reconstructor.reconstruct(circle(0));

        assertEquals(Source.YAW_RATE, o.source());
        double radius = 1000 / (2 * Math.PI);
        // the point half a lap away is one diameter from the start
        int half = o.x().length / 2;
        assertEquals(2 * radius, Math.hypot(o.x()[half] - o.x()[0], o.y()[half] - o.y()[0]), 0.5);
        assertEquals(0, o.headingClosureDeg(), 1e-6);
        assertTrue(o.positionClosureM() < 0.5, "gap " + o.positionClosureM());
    }

    @Test
    void closureRemovesGyroBias() {
        // 0.2 deg/s bias over 50 s = 10 deg heading error
        TrackOutline o = reconstructor.reconstruct(circle(0.2));

        assertEquals(10, o.headingClosureDeg(), 0.01);
        // At constant speed a constant bias grows linearly with distance, so the heading
        // correction alone already closes the loop.
        assertTrue(o.positionClosureM() < 0.5, "gap " + o.positionClosureM());
        int n = o.x().length;
        assertEquals(0, Math.hypot(o.x()[n - 1] - o.x()[0], o.y()[n - 1] - o.y()[0]), 1e-9);
        double radius = 1000 / (2 * Math.PI);
        assertEquals(2 * radius, Math.hypot(o.x()[n / 2] - o.x()[0], o.y()[n / 2] - o.y()[0]), 0.5);
    }

    @Test
    void noOutlineWithoutGpsOrYaw() {
        LapData lap = SyntheticLaps.lap("lap-1", 500, 50, true, SyntheticLaps.constant(100));
        assertTrue(reconstructor.reconstruct(lap).isEmpty());
    }
}
