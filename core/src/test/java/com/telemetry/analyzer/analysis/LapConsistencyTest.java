package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LapConsistencyTest {

    @Test
    void formulasOnPlainNumbers() {
        // mean 92, median 91, deviations -2, -1, 3: s = sqrt(14 / 2) = sqrt(7)
        LapConsistency c = LapConsistency.of(new String[]{"a", "b", "c"}, new double[]{90, 91, 95},
                new double[][]{{30, 60}, {31, 60}, {30, 65}});
        assertEquals(3, c.lapCount());
        assertEquals(92, c.meanLapTime(), 1e-12);
        assertEquals(91, c.medianLapTime(), 1e-12);
        assertEquals(Math.sqrt(7), c.lapTimeStdDev(), 1e-12);
        assertEquals(100 * Math.sqrt(7) / 92, c.coefficientOfVariationPercent(), 1e-12);
        assertEquals(5, c.spread(), 1e-12);
        // sector 1: 30, 31, 30 -> s = sqrt((1/9 + 4/9 + 1/9) / 2) = sqrt(1/3); sector 2: 60, 60, 65 -> sqrt(25/3)
        assertArrayEquals(new double[]{Math.sqrt(1.0 / 3), Math.sqrt(25.0 / 3)}, c.sectorStdDevs(), 1e-12);
        assertEquals(2, c.leastConsistentSector());
    }

    @Test
    void evenLapCountMedianAveragesTheMiddlePair() {
        // Unsorted input; sorted 100, 101, 103, 106: median (101 + 103) / 2 = 102, mean 102.5,
        // deviations -2.5, -1.5, 0.5, 3.5: s = sqrt(21 / 3) = sqrt(7)
        LapConsistency c = LapConsistency.of(new String[]{"a", "b", "c", "d"}, new double[]{103, 100, 106, 101},
                new double[][]{{50, 53}, {50, 50}, {52, 54}, {50, 51}});
        assertEquals(102.5, c.meanLapTime(), 1e-12);
        assertEquals(102, c.medianLapTime(), 1e-12);
        assertEquals(Math.sqrt(7), c.lapTimeStdDev(), 1e-12);
        assertEquals(6, c.spread(), 1e-12);
        // sector 1: 50, 50, 52, 50 -> s = 1; sector 2: 53, 50, 54, 51 -> s = sqrt(10 / 3)
        assertArrayEquals(new double[]{1, Math.sqrt(10.0 / 3)}, c.sectorStdDevs(), 1e-12);
        assertEquals(2, c.leastConsistentSector());
    }

    @Test
    void sessionConsistencyUsesCompleteLapsAndDistanceSectors() {
        // 1000 m laps, two 500 m sectors. 144 km/h = 40 m/s -> 12.5 s per sector, 120 km/h -> 15 s, 90 km/h -> 20 s.
        LapData stub = SyntheticLaps.lap("lap-1", 15, 50, true, SyntheticLaps.constant(10));
        LapData a = SyntheticLaps.lap("lap-2", 1000, 50, true, SyntheticLaps.constant(144));           // 12.5 + 12.5
        LapData b = SyntheticLaps.lap("lap-3", 1000, 50, true, halves(144, 120));                     // 12.5 + 15
        LapData c = SyntheticLaps.lap("lap-4", 1000, 50, true, halves(144, 90));                      // 12.5 + 20
        LapData d = SyntheticLaps.lap("lap-5", 1000, 50, false, SyntheticLaps.constant(120));         // 15 + 15
        SessionData session = new SessionData("s", "s.csv", Instant.now(), List.of(stub, a, b, c, d));

        LapConsistency r = SessionLaps.consistency(session, 2).orElseThrow();

        assertEquals(4, r.lapCount());
        assertArrayEquals(new String[]{"lap-2", "lap-3", "lap-4", "lap-5"}, r.lapIds());
        // lap times 25, 27.5, 32.5, 30: mean = median = 28.75, deviations -3.75, -1.25, 3.75, 1.25
        assertEquals(28.75, r.meanLapTime(), 0.01);
        assertEquals(28.75, r.medianLapTime(), 0.01);
        double s = Math.sqrt(31.25 / 3);
        assertEquals(s, r.lapTimeStdDev(), 0.01);
        assertEquals(100 * s / 28.75, r.coefficientOfVariationPercent(), 0.05);
        assertEquals(7.5, r.spread(), 0.01);
        // sector 1: 12.5, 12.5, 12.5, 15 -> sqrt(4.6875 / 3) = 1.25; sector 2: 12.5, 15, 20, 15 -> sqrt(29.6875 / 3)
        assertEquals(1.25, r.sectorStdDevs()[0], 0.01);
        assertEquals(Math.sqrt(29.6875 / 3), r.sectorStdDevs()[1], 0.01);
        assertEquals(2, r.leastConsistentSector());
    }

    @Test
    void emptyWithFewerThanTwoCompleteLaps() {
        LapData stub = SyntheticLaps.lap("lap-1", 15, 50, true, SyntheticLaps.constant(10));
        LapData a = SyntheticLaps.lap("lap-2", 1000, 50, true, SyntheticLaps.constant(144));
        assertTrue(SessionLaps.consistency(new SessionData("s", "s.csv", Instant.now(), List.of(a)), 10).isEmpty());
        assertTrue(SessionLaps.consistency(new SessionData("s", "s.csv", Instant.now(), List.of(stub, a)), 10).isEmpty());
        assertTrue(SessionLaps.consistency(new SessionData("s", "s.csv", Instant.now(), List.of()), 10).isEmpty());
    }

    @Test
    void outLapAndInLapAreLeftOut() {
        // 1000 m laps, two 500 m sectors. lap-1 leaves the pits at 60 km/h (first 200 m: 12 s, total
        // 32 s), lap-6 enters them at 50 km/h (last 200 m: 14.4 s, total 34.4 s).
        LapData out = SyntheticLaps.lap("lap-1", 1000, 50, true, SyntheticLaps.pitExit(60, 200, 144));
        LapData a = SyntheticLaps.lap("lap-2", 1000, 50, true, SyntheticLaps.constant(144));           // 25 s
        LapData b = SyntheticLaps.lap("lap-3", 1000, 50, true, SyntheticLaps.constant(144));           // 25 s
        LapData c = SyntheticLaps.lap("lap-4", 1000, 50, true, halves(144, 120));                      // 27.5 s
        LapData d = SyntheticLaps.lap("lap-5", 1000, 50, true, SyntheticLaps.constant(120));           // 30 s
        LapData in = SyntheticLaps.lap("lap-6", 1000, 50, true, SyntheticLaps.pitEntry(144, 800, 50));
        SessionData session = new SessionData("s", "s.csv", Instant.now(), List.of(out, a, b, c, d, in));

        LapConsistency r = SessionLaps.consistency(session, 2).orElseThrow();

        // Entry speeds 60, 144, 144, 144, 120, 144: each lap is compared with the median of the
        // others, 144 for every lap, so an out-lap starts below 86.4 km/h.
        // Exit speeds 144, 144, 144, 120, 120, 50: the median of the others is 144 for every lap
        // (for lap-4, of 144, 144, 144, 120, 50), so an in-lap ends below 86.4 km/h.
        assertEquals(List.of(
                new LapConsistency.ExcludedLap("lap-1", LapConsistency.OUT_LAP, 60, 144),
                new LapConsistency.ExcludedLap("lap-6", LapConsistency.IN_LAP, 144, 50)), r.excludedLaps());
        assertEquals(4, r.lapCount());
        assertArrayEquals(new String[]{"lap-2", "lap-3", "lap-4", "lap-5"}, r.lapIds());
        // Flying laps 25, 25, 27.5, 30 s (even n): median (25 + 27.5) / 2 = 26.25 differs from the
        // mean 26.875; deviations -1.875, -1.875, 0.625, 3.125.
        assertEquals(26.875, r.meanLapTime(), 0.01);
        assertEquals(26.25, r.medianLapTime(), 0.01);
        assertEquals(Math.sqrt(17.1875 / 3), r.lapTimeStdDev(), 0.01);
        assertEquals(5, r.spread(), 0.01);
        // The fastest lap and the theoretical best still see every complete lap.
        assertEquals(6, SessionLaps.completeLaps(session).size());
    }

    @Test
    void pitSpeedRuleComparesEachLapWithTheMedianOfTheOthers() {
        // Two laps: each is compared with the other. 80 < 0.6 * 177 = 106.2 -> out-lap; a median
        // that included the lap itself, (80 + 177) / 2 = 128.5, would give 77.1 and keep it.
        assertEquals(List.of(new LapConsistency.ExcludedLap("a", LapConsistency.OUT_LAP, 80, 177)),
                LapConsistency.outAndInLaps(new String[]{"a", "b"}, new double[]{80, 177}, new double[]{177, 177}));

        // Even number of other laps, entry speeds 80, 100, 110, 200, 210:
        //  a (80):  others 100, 110, 200, 210 -> median (110 + 200) / 2 = 155, limit 93 -> out-lap
        //           (the lower middle value 110 would give 66 and keep it);
        //  b (100): others 80, 110, 200, 210 -> median 155, limit 93 -> flying lap
        //           (the upper middle value 200 would give 120 and exclude it).
        assertEquals(List.of(new LapConsistency.ExcludedLap("a", LapConsistency.OUT_LAP, 80, 200)),
                LapConsistency.outAndInLaps(new String[]{"a", "b", "c", "d", "e"},
                        new double[]{80, 100, 110, 200, 210}, new double[]{200, 200, 200, 200, 200}));

        // The median, not the mean: exit speeds 300, 100, 110, 70. For d the others are 300, 100,
        // 110: median 110, limit 66 -> flying lap; their mean 170 would give 102 and exclude it.
        assertTrue(LapConsistency.outAndInLaps(new String[]{"a", "b", "c", "d"},
                new double[]{200, 200, 200, 200}, new double[]{300, 100, 110, 70}).isEmpty());

        // A lap that starts and ends slowly is both.
        assertEquals(List.of(new LapConsistency.ExcludedLap("a", LapConsistency.OUT_AND_IN_LAP, 50, 40)),
                LapConsistency.outAndInLaps(new String[]{"a", "b", "c"}, new double[]{50, 150, 150},
                        new double[]{40, 150, 150}));
        // A single lap has nothing to be compared with; no lap gives no result.
        assertTrue(LapConsistency.outAndInLaps(new String[]{"a"}, new double[]{40}, new double[]{40}).isEmpty());
        assertTrue(LapConsistency.outAndInLaps(new String[0], new double[0], new double[0]).isEmpty());
    }

    @Test
    void aSharedLineCrossingIsJudgedOnce() {
        // Line crossings at 40, 160, 95, 170 km/h: lap a runs from the first to the second, b from
        // the second to the third, c from the third to the fourth.
        //  a: entry 40 < 0.6 * median(160, 95) = 76.5 -> out-lap
        //  b: exit 95 < 0.6 * median(160, 170) = 99 -> in-lap
        //  c: entry 95, 0.6 * median(40, 160) = 60 -> not slow on its own, but it starts at the
        //     crossing that ends the in-lap b, so it is an out-lap too.
        String[] ids = {"a", "b", "c"};
        double[] entry = {40, 160, 95};
        double[] exit = {160, 95, 170};
        assertEquals(List.of(
                        new LapConsistency.ExcludedLap("a", LapConsistency.OUT_LAP, 40, 160),
                        new LapConsistency.ExcludedLap("b", LapConsistency.IN_LAP, 160, 95),
                        new LapConsistency.ExcludedLap("c", LapConsistency.OUT_LAP, 95, 170)),
                LapConsistency.outAndInLaps(ids, entry, exit, new boolean[]{false, true, true}));
        // Laps that do not follow each other are judged on their own speeds only.
        assertEquals(List.of(
                        new LapConsistency.ExcludedLap("a", LapConsistency.OUT_LAP, 40, 160),
                        new LapConsistency.ExcludedLap("b", LapConsistency.IN_LAP, 160, 95)),
                LapConsistency.outAndInLaps(ids, entry, exit));
        // It works backwards as well: crossings at 170, 95, 150, 150.
        //  b: entry 95 < 0.6 * median(170, 150) = 96 -> out-lap
        //  a: exit 95, 0.6 * median(150, 150) = 90 -> not slow on its own, but b starts there.
        String[] ids2 = {"a", "b", "c"};
        double[] entry2 = {170, 95, 150};
        double[] exit2 = {95, 150, 150};
        assertEquals(List.of(new LapConsistency.ExcludedLap("b", LapConsistency.OUT_LAP, 95, 150)),
                LapConsistency.outAndInLaps(ids2, entry2, exit2));
        assertEquals(List.of(
                        new LapConsistency.ExcludedLap("a", LapConsistency.IN_LAP, 170, 95),
                        new LapConsistency.ExcludedLap("b", LapConsistency.OUT_LAP, 95, 150)),
                LapConsistency.outAndInLaps(ids2, entry2, exit2, new boolean[]{false, true, true}));
    }

    @Test
    void twoStintsKeepOnlyTheFlyingLap() {
        // 1000 m laps: out-lap leaving the pits at 80 km/h, in-lap ending at 80 km/h, out-lap
        // starting at 80 km/h, flying lap. Entry speeds 80, 160, 80, 160: a median over all laps
        // (120, limit 72) would keep both out-laps; the median of the others (160) excludes them.
        LapData out1 = SyntheticLaps.lap("lap-1", 1000, 50, true, SyntheticLaps.pitExit(80, 200, 160));
        LapData in = SyntheticLaps.lap("lap-2", 1000, 50, true, SyntheticLaps.pitEntry(160, 800, 80));
        LapData out2 = SyntheticLaps.lap("lap-3", 1000, 50, true, SyntheticLaps.pitExit(80, 200, 160));
        LapData flying = SyntheticLaps.lap("lap-4", 1000, 50, true, SyntheticLaps.constant(160));
        SessionData session = new SessionData("s", "s.csv", Instant.now(), List.of(out1, in, out2, flying));

        assertEquals(List.of(
                new LapConsistency.ExcludedLap("lap-1", LapConsistency.OUT_LAP, 80, 160),
                new LapConsistency.ExcludedLap("lap-2", LapConsistency.IN_LAP, 160, 80),
                new LapConsistency.ExcludedLap("lap-3", LapConsistency.OUT_LAP, 80, 160)), SessionLaps.excludedLaps(session));
        assertTrue(SessionLaps.consistency(session, 10).isEmpty());

        // Two complete laps, an out-lap at 80 km/h and a flying lap: 80 < 0.6 * 160.
        SessionData two = new SessionData("s", "s.csv", Instant.now(), List.of(out1, flying));
        assertEquals(List.of(new LapConsistency.ExcludedLap("lap-1", LapConsistency.OUT_LAP, 80, 160)),
                SessionLaps.excludedLaps(two));
        assertTrue(SessionLaps.consistency(two, 10).isEmpty());
        // A lap that is not complete takes no part (the 10 km/h stub before lap-1 changes nothing),
        // and a single complete lap has nothing to be compared with.
        LapData stub = SyntheticLaps.lap("lap-0", 15, 50, true, SyntheticLaps.constant(10));
        assertEquals(List.of(new LapConsistency.ExcludedLap("lap-1", LapConsistency.OUT_LAP, 80, 160)),
                SessionLaps.excludedLaps(new SessionData("s", "s.csv", Instant.now(), List.of(stub, out1, flying))));
        assertTrue(SessionLaps.excludedLaps(new SessionData("s", "s.csv", Instant.now(), List.of(out1))).isEmpty());
    }

    @Test
    void emptyWithFewerThanTwoFlyingLaps() {
        LapData out = SyntheticLaps.lap("lap-1", 1000, 50, true, SyntheticLaps.pitExit(60, 200, 144));
        LapData a = SyntheticLaps.lap("lap-2", 1000, 50, true, SyntheticLaps.constant(144));
        LapData in = SyntheticLaps.lap("lap-3", 1000, 50, true, SyntheticLaps.pitEntry(144, 800, 50));
        // Three complete laps, but only lap-2 is a flying lap; the laps left out are still known.
        SessionData three = new SessionData("s", "s.csv", Instant.now(), List.of(out, a, in));
        assertTrue(SessionLaps.consistency(three, 10).isEmpty());
        assertEquals(List.of("lap-1", "lap-3"),
                SessionLaps.excludedLaps(three).stream().map(LapConsistency.ExcludedLap::lapId).toList());
        // Two complete laps, one of them an out-lap (entry 60 < 0.6 * 144 = 86.4 km/h).
        assertTrue(SessionLaps.consistency(new SessionData("s", "s.csv", Instant.now(), List.of(out, a)), 10)
                .isEmpty());
        // The record keeps working without exclusions.
        LapConsistency plain = LapConsistency.of(new String[]{"a", "b"}, new double[]{90, 91},
                new double[][]{{90}, {91}});
        assertTrue(plain.excludedLaps().isEmpty());
    }

    private static SyntheticLaps.Profile halves(double firstKmh, double secondKmh) {
        return SyntheticLaps.speed(d -> d < 500 ? firstKmh : secondKmh);
    }
}
