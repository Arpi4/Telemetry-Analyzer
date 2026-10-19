package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.analysis.DrivingEvents.BrakingPointDelta;
import com.telemetry.analyzer.analysis.DrivingEvents.BrakingZone;
import com.telemetry.analyzer.analysis.DrivingEvents.CornerApex;
import com.telemetry.analyzer.analysis.DrivingEvents.DrivingStyleMetrics;
import com.telemetry.analyzer.domain.LapData;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule-based recognition of driving patterns in a single lap: braking zones, corner apexes and
 * time-weighted pedal usage, plus matching of braking points between two laps.
 */
public class DrivingEventDetector {

    /** Brake pedal hysteresis: a zone opens at ON and closes when the pedal drops below OFF. */
    public static final double BRAKE_ON_PERCENT = 10;
    public static final double BRAKE_OFF_PERCENT = 5;
    /** Releases shorter than this inside one braking manoeuvre (e.g. ABS modulation) are bridged. */
    public static final double BRAKE_MERGE_GAP_S = 0.2;
    public static final double BRAKE_MIN_DURATION_S = 0.25;

    /** A speed minimum counts as a corner when speed falls and then recovers by at least this much. */
    public static final double CORNER_PROMINENCE_KMH = 10;
    /** Speed is smoothed over this distance before apex search to suppress sensor noise. */
    public static final double CORNER_SMOOTHING_M = 20;
    private static final double CORNER_GRID_STEP_M = 5;

    public static final double FULL_THROTTLE_PERCENT = 98;
    public static final double PEDAL_IDLE_PERCENT = 5;
    public static final double PEDAL_OVERLAP_PERCENT = 10;
    public static final double COASTING_MIN_SPEED_KMH = 30;

    /** Braking zones further apart than this (after normalisation) are not paired. */
    public static final double BRAKING_MATCH_WINDOW_M = 80;

    public DrivingEvents detect(LapData lap) {
        return detect(LapTrace.of(lap));
    }

    public DrivingEvents detect(LapTrace trace) {
        List<BrakingZone> zones = brakingZones(trace);
        List<CornerApex> corners = corners(trace);
        return new DrivingEvents(trace.lapId(), zones, corners, metrics(trace));
    }

    public List<BrakingZone> brakingZones(LapTrace t) {
        int n = t.size();
        List<int[]> spans = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < n; i++) {
            double b = t.brake()[i];
            if (start < 0 && b >= BRAKE_ON_PERCENT) {
                start = i;
            } else if (start >= 0 && b < BRAKE_OFF_PERCENT) {
                spans.add(new int[]{start, i});
                start = -1;
            }
        }
        if (start >= 0) {
            spans.add(new int[]{start, n - 1});
        }

        List<int[]> merged = new ArrayList<>();
        for (int[] span : spans) {
            if (!merged.isEmpty()) {
                int[] last = merged.getLast();
                if (t.elapsed()[span[0]] - t.elapsed()[last[1]] < BRAKE_MERGE_GAP_S) {
                    last[1] = span[1];
                    continue;
                }
            }
            merged.add(span);
        }

        List<BrakingZone> zones = new ArrayList<>();
        for (int[] span : merged) {
            int s = span[0];
            int e = span[1];
            double duration = t.elapsed()[e] - t.elapsed()[s];
            if (duration < BRAKE_MIN_DURATION_S) {
                continue;
            }
            double minSpeed = Double.MAX_VALUE;
            double peak = 0;
            for (int i = s; i <= e; i++) {
                minSpeed = Math.min(minSpeed, t.speed()[i]);
                peak = Math.max(peak, t.brake()[i]);
            }
            zones.add(new BrakingZone(zones.size() + 1, t.distance()[s], t.distance()[e],
                    t.elapsed()[s], duration, t.speed()[s], minSpeed, peak));
        }
        return zones;
    }

    /**
     * Hysteresis extremum search on distance-resampled, smoothed speed: after the speed has
     * dropped {@link #CORNER_PROMINENCE_KMH} below the last maximum we track the minimum, and
     * the minimum is accepted as an apex once speed has risen the same amount above it.
     */
    public List<CornerApex> corners(LapTrace t) {
        if (t.size() < 3 || t.lapLength() <= CORNER_GRID_STEP_M) {
            return List.of();
        }
        double[] grid = Interpolation.grid(t.lapLength(), CORNER_GRID_STEP_M);
        double[] v = movingAverage(Interpolation.resample(t.distance(), t.speed(), grid),
                (int) Math.round(CORNER_SMOOTHING_M / CORNER_GRID_STEP_M));
        double[] gear = Interpolation.resample(t.distance(), t.gear(), grid);

        List<int[]> apexes = new ArrayList<>(); // {apexIdx, entryMaxIdx}
        int maxIdx = 0;
        int minIdx = 0;
        boolean descending = false;
        for (int k = 1; k < v.length; k++) {
            if (!descending) {
                if (v[k] > v[maxIdx]) {
                    maxIdx = k;
                } else if (v[k] < v[maxIdx] - CORNER_PROMINENCE_KMH) {
                    descending = true;
                    minIdx = k;
                }
            } else {
                if (v[k] < v[minIdx]) {
                    minIdx = k;
                } else if (v[k] > v[minIdx] + CORNER_PROMINENCE_KMH) {
                    apexes.add(new int[]{minIdx, maxIdx});
                    descending = false;
                    maxIdx = k;
                }
            }
        }

        List<CornerApex> out = new ArrayList<>();
        for (int a = 0; a < apexes.size(); a++) {
            int apex = apexes.get(a)[0];
            int nextApex = a + 1 < apexes.size() ? apexes.get(a + 1)[0] : v.length;
            double exit = v[apex];
            for (int k = apex; k < nextApex; k++) {
                exit = Math.max(exit, v[k]);
            }
            out.add(new CornerApex(a + 1, grid[apex], v[apex], v[apexes.get(a)[1]], exit,
                    (int) Math.round(gear[apex])));
        }
        return out;
    }

    public DrivingStyleMetrics metrics(LapTrace t) {
        int n = t.size();
        double total = 0;
        double full = 0;
        double braking = 0;
        double coasting = 0;
        double overlap = 0;
        double maxSpeed = 0;
        int gearChanges = 0;
        for (int i = 0; i < n; i++) {
            maxSpeed = Math.max(maxSpeed, t.speed()[i]);
            if (i > 0 && t.gear()[i] != t.gear()[i - 1]) {
                gearChanges++;
            }
            if (i == n - 1) {
                break;
            }
            double dt = Math.max(0, t.elapsed()[i + 1] - t.elapsed()[i]);
            double thr = t.throttle()[i];
            double brk = t.brake()[i];
            total += dt;
            if (thr >= FULL_THROTTLE_PERCENT) {
                full += dt;
            }
            if (brk >= BRAKE_ON_PERCENT) {
                braking += dt;
            }
            if (thr < PEDAL_IDLE_PERCENT && brk < PEDAL_IDLE_PERCENT && t.speed()[i] > COASTING_MIN_SPEED_KMH) {
                coasting += dt;
            }
            if (thr >= PEDAL_OVERLAP_PERCENT && brk >= PEDAL_OVERLAP_PERCENT) {
                overlap += dt;
            }
        }
        double lapTime = t.lapTime();
        double avg = lapTime > 0 ? t.lapLength() / lapTime * 3.6 : 0;
        return new DrivingStyleMetrics(lapTime, t.lapLength(), avg, maxSpeed,
                pct(full, total), pct(braking, total), pct(coasting, total), pct(overlap, total), gearChanges);
    }

    /**
     * Pairs braking zones of two laps in track order. Compare-lap positions are scaled to the
     * reference lap length first, so a longer racing line does not shift every later zone.
     */
    public List<BrakingPointDelta> compareBrakingPoints(DrivingEvents reference, DrivingEvents compare) {
        double refLen = reference.metrics().lapLength();
        double cmpLen = compare.metrics().lapLength();
        double scale = cmpLen > 0 ? refLen / cmpLen : 1;
        List<BrakingZone> cmpZones = compare.brakingZones();
        List<BrakingPointDelta> out = new ArrayList<>();
        int searchFrom = 0;
        for (BrakingZone r : reference.brakingZones()) {
            int best = -1;
            double bestGap = BRAKING_MATCH_WINDOW_M;
            for (int j = searchFrom; j < cmpZones.size(); j++) {
                double gap = Math.abs(cmpZones.get(j).startDistance() * scale - r.startDistance());
                if (gap <= bestGap) {
                    bestGap = gap;
                    best = j;
                }
            }
            if (best < 0) {
                continue;
            }
            BrakingZone c = cmpZones.get(best);
            double cmpStart = c.startDistance() * scale;
            out.add(new BrakingPointDelta(r.index(), c.index(), r.startDistance(), cmpStart,
                    cmpStart - r.startDistance(), c.minSpeed() - r.minSpeed(), c.duration() - r.duration()));
            searchFrom = best + 1;
        }
        return out;
    }

    static double[] movingAverage(double[] values, int window) {
        if (window <= 1) {
            return values.clone();
        }
        int half = window / 2;
        double[] prefix = new double[values.length + 1];
        for (int i = 0; i < values.length; i++) {
            prefix[i + 1] = prefix[i] + values[i];
        }
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            int lo = Math.max(0, i - half);
            int hi = Math.min(values.length - 1, i + half);
            out[i] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1);
        }
        return out;
    }

    private static double pct(double part, double total) {
        return total > 0 ? 100.0 * part / total : 0;
    }
}
