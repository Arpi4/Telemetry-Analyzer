package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionLapsTest {

    @Test
    void dequantizeSpreadsPlateausWhileMoving() {
        double[] d = {0, 0, 0, 1, 1, 1, 2};
        double[] v = {36, 36, 36, 36, 36, 36, 36};
        double[] t = {0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6};
        double[] out = LapTrace.dequantize(d, v, t);
        assertArrayEquals(new double[]{0, 1.0 / 3, 2.0 / 3, 1, 4.0 / 3, 5.0 / 3, 2}, out, 1e-12);
    }

    @Test
    void dequantizeKeepsStandstill() {
        double[] d = {5, 5, 5, 5, 6};
        double[] v = {0, 0, 0, 0, 3};
        double[] t = {0, 1, 2, 3, 4};
        assertArrayEquals(d, LapTrace.dequantize(d, v, t), 1e-12);
    }

    @Test
    void incompleteLapsAreExcludedAndBestSectorsCombine() {
        // lap-1: pit-lane stub; lap-2 fast in the first half (22.5 s); lap-3 fast in the second half (22.79 s)
        LapData stub = SyntheticLaps.lap("lap-1", 15, 50, true, SyntheticLaps.constant(10));
        LapData a = SyntheticLaps.lap("lap-2", 1000, 50, true, SyntheticLaps.speed(d -> d < 500 ? 180 : 144));
        LapData b = SyntheticLaps.lap("lap-3", 1000, 50, true, SyntheticLaps.speed(d -> d < 500 ? 144 : 175));
        LapData c = SyntheticLaps.lap("lap-4", 1000, 50, true, SyntheticLaps.constant(144));
        SessionData session = new SessionData("s", "s.csv", Instant.now(), List.of(stub, a, b, c));

        assertEquals(List.of(a, b, c), SessionLaps.completeLaps(session));

        var best = SessionLaps.theoreticalBest(session, 2).orElseThrow();
        // 500 m at 180 km/h (10 s) + 500 m at 175 km/h (10.29 s): faster than any driven lap
        assertEquals(10 + 500 / (175 / 3.6), best.lapTime(), 0.02);
        assertArrayEquals(new String[]{"lap-2", "lap-3"}, best.sectorLapIds());
        assertEquals("lap-2", SessionLaps.fastestLap(session).orElseThrow().lapId());
    }
}
