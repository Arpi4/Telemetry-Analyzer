package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Session-level lap bookkeeping: which laps are complete, the fastest lap, sector times, the
 * theoretical best lap (sum of the best sector times driven in the session) and lap-time
 * consistency.
 */
public final class SessionLaps {

    /** A lap counts as complete if its length is within this share of the session median. */
    public static final double COMPLETE_LAP_TOLERANCE = 0.03;

    private SessionLaps() {
    }

    public static List<LapData> completeLaps(SessionData session) {
        List<LapData> laps = session.laps();
        if (laps.isEmpty()) {
            return List.of();
        }
        double[] lengths = laps.stream().mapToDouble(l -> LapTrace.of(l).lapLength()).toArray();
        double median = median(lengths);
        List<LapData> out = new ArrayList<>();
        for (int i = 0; i < laps.size(); i++) {
            if (median > 0 && Math.abs(lengths[i] - median) <= COMPLETE_LAP_TOLERANCE * median) {
                out.add(laps.get(i));
            }
        }
        return out;
    }

    public static boolean isComplete(SessionData session, LapData lap) {
        return completeLaps(session).contains(lap);
    }

    public static Optional<LapData> fastestLap(SessionData session) {
        LapData best = null;
        double bestTime = Double.MAX_VALUE;
        for (LapData lap : completeLaps(session)) {
            double t = LapTrace.of(lap).lapTime();
            if (t > 0 && t < bestTime) {
                bestTime = t;
                best = lap;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Time spent in each of {@code sectors} equal-length parts of the lap. Each lap is split by
     * its own length, so laps with slightly different lines remain comparable.
     */
    public static double[] sectorTimes(LapTrace trace, int sectors) {
        double[] bounds = new double[sectors + 1];
        for (int s = 0; s <= sectors; s++) {
            bounds[s] = trace.lapLength() * s / sectors;
        }
        double[] at = Interpolation.resample(trace.distance(), trace.elapsed(), bounds);
        at[sectors] = trace.lapTime();
        double[] out = new double[sectors];
        for (int s = 0; s < sectors; s++) {
            out[s] = at[s + 1] - at[s];
        }
        return out;
    }

    /** Sum of the per-sector minima over all complete laps; empty when there is no complete lap. */
    public static Optional<TheoreticalBest> theoreticalBest(SessionData session, int sectors) {
        List<LapData> laps = completeLaps(session);
        if (laps.isEmpty()) {
            return Optional.empty();
        }
        double[] best = new double[sectors];
        String[] bestLap = new String[sectors];
        Arrays.fill(best, Double.MAX_VALUE);
        for (LapData lap : laps) {
            double[] st = sectorTimes(LapTrace.of(lap), sectors);
            for (int s = 0; s < sectors; s++) {
                if (st[s] < best[s]) {
                    best[s] = st[s];
                    bestLap[s] = lap.lapId();
                }
            }
        }
        return Optional.of(new TheoreticalBest(Arrays.stream(best).sum(), best, bestLap));
    }

    public record TheoreticalBest(double lapTime, double[] sectorTimes, String[] sectorLapIds) {
    }

    /**
     * The complete laps (with a lap time above 0) that are out-laps or in-laps, in session order,
     * by the rule in {@link LapConsistency}: the laps the consistency figures leave out. Laps that
     * follow each other in the session share the line crossing between them. Available whether
     * or not enough flying laps remain for {@link #consistency}; empty with fewer than two such
     * complete laps.
     */
    public static List<LapConsistency.ExcludedLap> excludedLaps(SessionData session) {
        return excludedLaps(timedCompleteLaps(session));
    }

    /** A complete lap with a lap time above 0, and its position in the session's lap list. */
    private record TimedLap(int sessionIndex, LapTrace trace) {
    }

    private static List<TimedLap> timedCompleteLaps(SessionData session) {
        List<LapData> all = session.laps();
        List<LapData> complete = completeLaps(session); // the same objects, in session order
        List<TimedLap> out = new ArrayList<>();
        for (int i = 0, j = 0; i < all.size() && j < complete.size(); i++) {
            if (all.get(i) == complete.get(j)) {
                j++;
                LapTrace trace = LapTrace.of(all.get(i));
                if (trace.lapTime() > 0) {
                    out.add(new TimedLap(i, trace));
                }
            }
        }
        return out;
    }

    private static List<LapConsistency.ExcludedLap> excludedLaps(List<TimedLap> laps) {
        int n = laps.size();
        String[] ids = new String[n];
        double[] entrySpeeds = new double[n];
        double[] exitSpeeds = new double[n];
        boolean[] startsAtPreviousEnd = new boolean[n];
        for (int i = 0; i < n; i++) {
            LapTrace trace = laps.get(i).trace();
            ids[i] = trace.lapId();
            entrySpeeds[i] = trace.speed()[0];
            exitSpeeds[i] = trace.speed()[trace.size() - 1];
            // the next lap of the session starts at the crossing that ends this one
            startsAtPreviousEnd[i] = i > 0 && laps.get(i).sessionIndex() == laps.get(i - 1).sessionIndex() + 1;
        }
        return LapConsistency.outAndInLaps(ids, entrySpeeds, exitSpeeds, startsAtPreviousEnd);
    }

    /**
     * Lap-time consistency over the flying laps: the complete laps without out-laps and in-laps
     * (rule and formulas in {@link LapConsistency}, which also lists the laps left out, as
     * {@link #excludedLaps} does); sector times use the same split as {@link #sectorTimes}.
     * Empty with fewer than two flying laps.
     */
    public static Optional<LapConsistency> consistency(SessionData session, int sectors) {
        List<TimedLap> complete = timedCompleteLaps(session);
        if (complete.size() < 2) {
            return Optional.empty();
        }
        List<LapConsistency.ExcludedLap> excluded = excludedLaps(complete);
        Set<String> excludedIds = new HashSet<>();
        excluded.forEach(e -> excludedIds.add(e.lapId()));
        List<LapTrace> flying = complete.stream()
                .map(TimedLap::trace)
                .filter(t -> !excludedIds.contains(t.lapId()))
                .toList();
        int n = flying.size();
        if (n < 2) {
            return Optional.empty();
        }
        String[] ids = new String[n];
        double[] times = new double[n];
        double[][] sectorTimes = new double[n][];
        for (int i = 0; i < n; i++) {
            LapTrace trace = flying.get(i);
            ids[i] = trace.lapId();
            times[i] = trace.lapTime();
            sectorTimes[i] = sectorTimes(trace, sectors);
        }
        return Optional.of(LapConsistency.of(ids, times, sectorTimes, excluded));
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }
}
