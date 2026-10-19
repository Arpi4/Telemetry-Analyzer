package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.analysis.DrivingEvents.BrakingPointDelta;
import com.telemetry.analyzer.analysis.SyntheticLaps.Sample;
import com.telemetry.analyzer.domain.LapData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrivingEventDetectorTest {

    private final DrivingEventDetector detector = new DrivingEventDetector();

    /**
     * 2000 m lap with two corners. Before each corner the driver brakes over 100 m from 200 to
     * 80 km/h (corner 1 at 600 m) and from 200 to 120 km/h (corner 2 at 1500 m), then accelerates.
     * {@code brakeShift} moves both braking points (positive = later).
     */
    private static SyntheticLaps.Profile twoCorners(double brakeShift) {
        return d -> {
            double b1 = 500 + brakeShift;
            double b2 = 1400 + brakeShift;
            if (d >= b1 && d < 600) {
                return new Sample(lerp(200, 80, (d - b1) / (600 - b1)), 0, 90, 3);
            }
            if (d >= 600 && d < 800) {
                return new Sample(lerp(80, 200, (d - 600) / 200), 100, 0, 4);
            }
            if (d >= b2 && d < 1500) {
                return new Sample(lerp(200, 120, (d - b2) / (1500 - b2)), 0, 70, 4);
            }
            if (d >= 1500 && d < 1700) {
                return new Sample(lerp(120, 200, (d - 1500) / 200), 100, 0, 5);
            }
            return new Sample(200, 100, 0, 6);
        };
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    @Test
    void detectsBrakingZonesAndCorners() {
        LapData lap = SyntheticLaps.lap("lap-1", 2000, 100, true, twoCorners(0));

        DrivingEvents events = detector.detect(lap);

        assertEquals(2, events.brakingZones().size());
        var z1 = events.brakingZones().get(0);
        assertEquals(500, z1.startDistance(), 3);
        assertEquals(80, z1.minSpeed(), 2);
        assertEquals(90, z1.peakBrake(), 1e-9);

        assertEquals(2, events.corners().size());
        assertEquals(600, events.corners().get(0).distance(), 15);
        // smoothing over 20 m lifts the V-shaped minimum by a few km/h
        assertEquals(80, events.corners().get(0).minSpeed(), 8);
        assertEquals(1500, events.corners().get(1).distance(), 15);
    }

    @Test
    void timeWeightedPedalShares() {
        LapData lap = SyntheticLaps.lap("lap-1", 2000, 100, true, twoCorners(0));

        var m = detector.detect(lap).metrics();

        assertTrue(m.brakingPercent() > 5 && m.brakingPercent() < 15, "braking " + m.brakingPercent());
        assertEquals(100 - m.brakingPercent(), m.fullThrottlePercent(), 0.1);
        assertEquals(0, m.coastingPercent(), 1e-9);
        assertEquals(m.lapLength() / m.lapTime() * 3.6, m.averageSpeed(), 1e-9);
        assertEquals(6, m.gearChanges()); // 6→3→4→6→4→5→6
    }

    @Test
    void laterBrakingShowsAsPositiveDelta() {
        DrivingEvents ref = detector.detect(SyntheticLaps.lap("ref", 2000, 100, true, twoCorners(0)));
        DrivingEvents cmp = detector.detect(SyntheticLaps.lap("cmp", 2000, 100, true, twoCorners(20)));

        List<BrakingPointDelta> deltas = detector.compareBrakingPoints(ref, cmp);

        assertEquals(2, deltas.size());
        assertEquals(20, deltas.get(0).startDeltaMeters(), 3);
        assertEquals(20, deltas.get(1).startDeltaMeters(), 3);
        assertTrue(deltas.get(0).durationDelta() < 0, "later braking means shorter braking");
    }
}
