package com.telemetry.analyzer.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lap-time consistency over the flying laps of a session (see {@link SessionLaps#consistency}).
 *
 * <p><b>Flying laps.</b> The statistics are taken over the complete laps (lap length within
 * {@link SessionLaps#COMPLETE_LAP_TOLERANCE} of the session median, lap time above 0) that are
 * neither an out-lap nor an in-lap. With v_in the speed of a lap's first sample (entry speed),
 * v_out the speed of its last sample (exit speed), and the medians taken over the <em>other</em>
 * complete laps (leave-one-out):
 * <ul>
 *   <li>out-lap: v_in &lt; {@value #PIT_SPEED_RATIO} · median of the other laps' v_in (the lap
 *       starts at the pit exit, at pit-lane speed);</li>
 *   <li>in-lap: v_out &lt; {@value #PIT_SPEED_RATIO} · median of the other laps' v_out (the lap
 *       ends in the pit lane);</li>
 *   <li>a lap can be both ({@link #OUT_AND_IN_LAP});</li>
 *   <li>a lap that directly follows another starts at the line crossing that ends the previous
 *       one (the same sample), so that crossing is judged once: when either of the two rules
 *       finds it slow, the earlier lap is an in-lap and the later one an out-lap.</li>
 * </ul>
 * A pit lane limiter is 60–80 km/h while a flying lap crosses the line far above 100 km/h on
 * nearly every circuit, so 60 % of the reference separates the two with a wide margin; a lap
 * starting or ending slowly on track (yellow flag, spin at the line) is excluded the same way,
 * which is what a consistency figure should do. Leaving the lap itself out of its reference keeps
 * a pit lap from pulling the reference down: with two complete laps each one is compared with the
 * other, and a session of two stints (half of the line crossings in the pit lane) still finds its
 * pit laps. A single pit-exit lap otherwise dominates the figures (Kyalami sample: standard
 * deviation 2.606 s with it, 0.158 s without). The rule is relative, so it cannot find pit laps
 * when most complete laps are pit laps, and a lap with no other complete lap is never excluded.
 * The excluded laps are listed in {@link #excludedLaps} with their reason; the fastest lap and
 * the theoretical best lap still use every complete lap.
 *
 * <p><b>Statistics.</b> With flying-lap times t_1..t_n (n &ge; 2) and the time t_{i,k} spent in
 * sector k of lap i (same equal-length sector split as {@link SessionLaps#sectorTimes}):
 * <ul>
 *   <li>mean: t̄ = (1/n) Σ t_i</li>
 *   <li>median: middle value of the sorted lap times, or the mean of the two middle values</li>
 *   <li>sample standard deviation: s = √( Σ (t_i − t̄)² / (n − 1) )</li>
 *   <li>coefficient of variation: CV = 100 · s / t̄ (%)</li>
 *   <li>spread: max t_i − min t_i (slowest minus fastest lap)</li>
 *   <li>sector standard deviation: s_k = √( Σ_i (t_{i,k} − t̄_k)² / (n − 1) ), t̄_k the mean of sector k</li>
 *   <li>least consistent sector: the 1-based k with the largest s_k (the first one on a tie)</li>
 * </ul>
 * All times are in seconds.
 *
 * @param lapCount     number of flying laps the figures are computed over
 * @param lapIds       those laps, in session order
 * @param excludedLaps complete laps left out as out-lap or in-lap, in session order (empty when none)
 */
public record LapConsistency(
        int lapCount,
        String[] lapIds,
        double meanLapTime,
        double medianLapTime,
        double lapTimeStdDev,
        double coefficientOfVariationPercent,
        double spread,
        double[] sectorStdDevs,
        int leastConsistentSector,
        List<ExcludedLap> excludedLaps
) {

    /**
     * Entry (exit) speed below this share of the median entry (exit) speed of the other complete
     * laps marks an out-lap (in-lap).
     */
    public static final double PIT_SPEED_RATIO = 0.6;

    /** Reason: the lap starts slowly (pit exit). */
    public static final String OUT_LAP = "out-lap";

    /** Reason: the lap ends slowly (pit entry). */
    public static final String IN_LAP = "in-lap";

    /** Reason: the lap starts and ends slowly (e.g. a pit-to-pit lap). */
    public static final String OUT_AND_IN_LAP = "out-lap and in-lap";

    /**
     * A complete lap left out of the consistency figures.
     *
     * @param lapId      the lap
     * @param reason     {@link #OUT_LAP}, {@link #IN_LAP} or {@link #OUT_AND_IN_LAP}
     * @param entrySpeed speed of the lap's first sample in km/h
     * @param exitSpeed  speed of the lap's last sample in km/h
     */
    public record ExcludedLap(String lapId, String reason, double entrySpeed, double exitSpeed) {
    }

    public LapConsistency {
        excludedLaps = excludedLaps == null ? List.of() : List.copyOf(excludedLaps);
    }

    /** Without excluded laps (every complete lap is a flying lap). */
    public LapConsistency(int lapCount, String[] lapIds, double meanLapTime, double medianLapTime,
                          double lapTimeStdDev, double coefficientOfVariationPercent, double spread,
                          double[] sectorStdDevs, int leastConsistentSector) {
        this(lapCount, lapIds, meanLapTime, medianLapTime, lapTimeStdDev, coefficientOfVariationPercent, spread,
                sectorStdDevs, leastConsistentSector, List.of());
    }

    /**
     * @param lapIds      laps in session order
     * @param lapTimes    lap time of each lap
     * @param sectorTimes {@code sectorTimes[i][k]}: time of lap i in sector k, same sector count for every lap
     */
    public static LapConsistency of(String[] lapIds, double[] lapTimes, double[][] sectorTimes) {
        return of(lapIds, lapTimes, sectorTimes, List.of());
    }

    /** As {@link #of(String[], double[], double[][])}, recording the laps that were left out. */
    public static LapConsistency of(String[] lapIds, double[] lapTimes, double[][] sectorTimes,
                                    List<ExcludedLap> excludedLaps) {
        int n = lapTimes.length;
        if (n < 2 || lapIds.length != n || sectorTimes.length != n) {
            throw new IllegalArgumentException("need at least two laps with one id and one sector row each");
        }
        int sectors = sectorTimes[0].length;
        double[] sectorStd = new double[sectors];
        int leastConsistent = 0;
        double[] column = new double[n];
        for (int k = 0; k < sectors; k++) {
            for (int i = 0; i < n; i++) {
                column[i] = sectorTimes[i][k];
            }
            sectorStd[k] = sampleStdDev(column);
            if (sectorStd[k] > sectorStd[leastConsistent]) {
                leastConsistent = k;
            }
        }
        double mean = mean(lapTimes);
        double std = sampleStdDev(lapTimes);
        double min = Arrays.stream(lapTimes).min().orElseThrow();
        double max = Arrays.stream(lapTimes).max().orElseThrow();
        return new LapConsistency(n, lapIds.clone(), mean, SessionLaps.median(lapTimes), std,
                mean > 0 ? 100 * std / mean : 0, max - min, sectorStd, sectors == 0 ? 0 : leastConsistent + 1,
                excludedLaps);
    }

    /**
     * Out-laps and in-laps by the rule in the class comment, for laps that do not follow each
     * other directly (no shared line crossing).
     *
     * @param lapIds      the complete laps, in session order
     * @param entrySpeeds speed of each lap's first sample (km/h)
     * @param exitSpeeds  speed of each lap's last sample (km/h)
     * @return the excluded laps in session order; laps not listed are flying laps
     */
    public static List<ExcludedLap> outAndInLaps(String[] lapIds, double[] entrySpeeds, double[] exitSpeeds) {
        return outAndInLaps(lapIds, entrySpeeds, exitSpeeds, new boolean[lapIds.length]);
    }

    /**
     * Out-laps and in-laps by the rule in the class comment.
     *
     * @param lapIds              the complete laps, in session order
     * @param entrySpeeds         speed of each lap's first sample (km/h)
     * @param exitSpeeds          speed of each lap's last sample (km/h)
     * @param startsAtPreviousEnd {@code startsAtPreviousEnd[i]}: lap i directly follows lap i − 1
     *                            of these arrays, so its first sample is the line crossing that
     *                            ends lap i − 1 (ignored for i = 0)
     * @return the excluded laps in session order; laps not listed are flying laps
     */
    public static List<ExcludedLap> outAndInLaps(String[] lapIds, double[] entrySpeeds, double[] exitSpeeds,
                                                 boolean[] startsAtPreviousEnd) {
        int n = lapIds.length;
        if (entrySpeeds.length != n || exitSpeeds.length != n || startsAtPreviousEnd.length != n) {
            throw new IllegalArgumentException("need one entry speed, exit speed and follow flag per lap");
        }
        boolean[] outLap = slowerThanTheOthers(entrySpeeds);
        boolean[] inLap = slowerThanTheOthers(exitSpeeds);
        for (int i = 1; i < n; i++) {
            if (startsAtPreviousEnd[i] && (outLap[i] || inLap[i - 1])) {
                outLap[i] = true;
                inLap[i - 1] = true;
            }
        }
        List<ExcludedLap> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (outLap[i] || inLap[i]) {
                String reason = outLap[i] && inLap[i] ? OUT_AND_IN_LAP : outLap[i] ? OUT_LAP : IN_LAP;
                out.add(new ExcludedLap(lapIds[i], reason, entrySpeeds[i], exitSpeeds[i]));
            }
        }
        return out;
    }

    /**
     * {@code speeds[i] < PIT_SPEED_RATIO · median of the other speeds}; never true for a single
     * value, which has nothing to be compared with.
     */
    private static boolean[] slowerThanTheOthers(double[] speeds) {
        int n = speeds.length;
        boolean[] slow = new boolean[n];
        if (n < 2) {
            return slow;
        }
        double[] others = new double[n - 1];
        for (int i = 0; i < n; i++) {
            for (int j = 0, k = 0; j < n; j++) {
                if (j != i) {
                    others[k++] = speeds[j];
                }
            }
            slow[i] = speeds[i] < PIT_SPEED_RATIO * SessionLaps.median(others);
        }
        return slow;
    }

    static double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    /** Two-pass: deviations from the mean are squared, which avoids cancellation for ~100 s laps. */
    static double sampleStdDev(double[] values) {
        double mean = mean(values);
        double sq = 0;
        for (double v : values) {
            sq += (v - mean) * (v - mean);
        }
        return Math.sqrt(sq / (values.length - 1));
    }
}
