package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.dto.SegmentDeltaDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LapComparatorTest {

    private final LapComparator comparator = new LapComparator();

    @Test
    void integratedDistanceOfConstantSpeed() {
        // 36 km/h = 10 m/s for 10 s
        double[] speed = new double[101];
        double[] time = new double[101];
        for (int i = 0; i <= 100; i++) {
            speed[i] = 36;
            time[i] = i * 0.1;
        }
        double[] d = LapTrace.integratedDistance(speed, time);
        assertEquals(100.0, d[100], 1e-9);
    }

    @Test
    void resampleUsesFirstArrivalOnPlateau() {
        double[] x = {0, 10, 10, 10, 20};
        double[] y = {0, 1, 2, 3, 4};
        double[] out = Interpolation.resample(x, y, new double[]{5, 10, 15});
        assertArrayEquals(new double[]{0.5, 1, 3.5}, out, 1e-12);
    }

    @Test
    void identicalLapsHaveZeroDelta() {
        LapData a = SyntheticLaps.lap("a", 1000, 50, true, SyntheticLaps.constant(144));
        AlignedLapComparison c = comparator.compare(a, a);
        for (double dt : c.deltaTime()) {
            assertEquals(0, dt, 1e-9);
        }
    }

    @Test
    void deltaGrowsOnlyWhereTheCompareLapIsSlower() {
        // 1000 m; compare lap is 72 km/h instead of 144 km/h on the second half.
        LapData ref = SyntheticLaps.lap("ref", 1000, 100, true, SyntheticLaps.constant(144));
        LapData cmp = SyntheticLaps.lap("cmp", 1000, 100, true,
                SyntheticLaps.speed(d -> d < 500 ? 144 : 72));

        AlignedLapComparison c = comparator.compare(ref, cmp);

        // first half: no loss; second half: 500 m at 20 m/s vs 40 m/s = 12.5 s more
        assertEquals(0, c.deltaTime()[indexOf(c, 400)], 0.02);
        assertEquals(12.5, c.finalDelta(), 0.02);
        assertEquals(c.lapTimeDelta(), c.finalDelta(), 0.02);
        assertTrue(c.distanceFromChannel());
    }

    @Test
    void sectorDeltasSumToFinalDelta() {
        LapData ref = SyntheticLaps.lap("ref", 1200, 100, true, SyntheticLaps.speed(d -> 100 + 50 * Math.sin(d / 100)));
        LapData cmp = SyntheticLaps.lap("cmp", 1200, 100, true, SyntheticLaps.speed(d -> 95 + 55 * Math.sin(d / 90)));

        AlignedLapComparison c = comparator.compare(ref, cmp);
        List<SegmentDeltaDto> sectors = comparator.sectorDeltas(c, 6);

        assertEquals(6, sectors.size());
        double sum = sectors.stream().mapToDouble(SegmentDeltaDto::deltaSeconds).sum();
        assertEquals(c.finalDelta(), sum, 1e-9);
        assertEquals(0.0, sectors.getFirst().distanceStartM(), 1e-9);
        assertEquals(1200.0, sectors.getLast().distanceEndM(), 1e-6);
    }

    @Test
    void worksWithoutDistanceChannelByIntegratingSpeed() {
        LapData ref = SyntheticLaps.lap("ref", 1000, 100, false, SyntheticLaps.constant(144));
        LapData cmp = SyntheticLaps.lap("cmp", 1000, 100, false, SyntheticLaps.speed(d -> d < 500 ? 144 : 72));

        AlignedLapComparison c = comparator.compare(ref, cmp);

        assertFalse(c.distanceFromChannel());
        assertEquals(1000, c.referenceLapLength(), 1.0);
        assertEquals(12.5, c.finalDelta(), 0.05);
    }

    @Test
    void normalisationRemovesFakeDeltaOfALongerLine() {
        // Same speed, but the compare lap drives 1 % more distance (wider line): 1.01 × lap time.
        LapData ref = SyntheticLaps.lap("ref", 1000, 100, true, SyntheticLaps.constant(144));
        LapData cmp = SyntheticLaps.lap("cmp", 1010, 100, true, SyntheticLaps.constant(144));

        AlignedLapComparison normalized = comparator.compare(LapTrace.of(ref), LapTrace.of(cmp), 2, true);
        AlignedLapComparison raw = comparator.compare(LapTrace.of(ref), LapTrace.of(cmp), 2, false);

        // Normalised: the 0.25 s are spread evenly over the lap and match the true lap time delta.
        assertEquals(0.25, normalized.finalDelta(), 0.01);
        assertEquals(0.125, normalized.deltaTime()[indexOf(normalized, 500)], 0.01);
        // Raw distance: the compare lap is cut off 10 m before its line, hiding the loss.
        assertEquals(0.0, raw.finalDelta(), 0.01);
    }

    private static int indexOf(AlignedLapComparison c, double distance) {
        for (int i = 0; i < c.size(); i++) {
            if (c.distance()[i] >= distance) {
                return i;
            }
        }
        return c.size() - 1;
    }
}
