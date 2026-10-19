package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Session-level lap bookkeeping: which laps are complete, the fastest lap, sector times and the
 * theoretical best lap (sum of the best sector times driven in the session).
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

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }
}
