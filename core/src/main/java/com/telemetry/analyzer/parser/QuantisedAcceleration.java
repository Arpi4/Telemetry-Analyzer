package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.TelemetryPoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Coarse (quantised) acceleration columns, and longitudinal acceleration derived from speed to
 * replace them. Used by {@link ChannelUnits#normalizeChannels}.
 *
 * <p><b>Quantised column.</b> The levels of an acceleration column are the whole numbers that each
 * hold more than {@value #MIN_SHARE_PER_LEVEL} of its non-empty values (0.1 %). The column is
 * quantised when it has at least {@value #MIN_VALUES} non-empty values, at most
 * {@value #MAX_DISTINCT_LEVELS} levels, and at least {@value #MIN_SHARE_ON_LEVELS} of the values
 * (99 %) on them. Assetto Corsa Competizione's MoTeC export writes G_LON this way: -2, -1, 0 and
 * 1 g, i.e. 1 g resolution, which is useless for a trace, plus a -40 collision spike in 9 of the
 * 113,406 samples of the Monza file. Such rare outliers, whole-number spikes or interpolated
 * values, are not levels and do not stop the detection however many distinct values they take;
 * the warning counts them. The test runs on the raw column values, before any unit scaling. A
 * genuine whole-number m/s² column spreads over far more than eight levels at track pace, and a
 * short hand-made file (fewer than {@value #MIN_VALUES} values) is never taken as quantised.
 *
 * <p><b>Longitudinal acceleration from speed.</b> For sample i with time t_i and speed v_i
 * (km/h, converted to m/s):
 * <pre>
 *   a_i = (v_b − v_a) / 3.6 / (t_b − t_a) / g0,   g0 = 9.80665 m/s²
 * </pre>
 * where a is the earliest and b the latest sample with |t − t_i| &le; {@value #HALF_WINDOW_S} s:
 * a central difference over a 0.5 s window. The window averages out the steps of a speed channel
 * written with 0.1 km/h resolution and held for several 200 Hz samples, while braking phases
 * (1–3 s) stay resolved. Details:
 * <ul>
 *   <li>The window always reaches at least the neighbouring samples, so a log slower than 4 Hz
 *       uses its neighbours.</li>
 *   <li>A time step longer than {@value #MAX_STEP_S} s (logger pause, merged files) or a step
 *       backwards (time reset) splits the samples into segments; the window never crosses a
 *       segment boundary. At the first and last sample of a segment it is one-sided (forward or
 *       backward difference over up to 0.25 s).</li>
 *   <li>A window spanning less than {@value #MIN_SPAN_S} s (a segment that short, or repeated
 *       timestamps) gives no value ({@code null}), so the quotient never divides by ~0. A
 *       non-finite speed also gives {@code null}.</li>
 *   <li>The derivative is taken over the continuous sample stream before it is split into laps,
 *       so a lap line is not a boundary; the sample shared by two laps has one value.</li>
 *   <li>Values are rounded to {@value #RESOLUTION_G} g, far below the error of the speed steps,
 *       so equal values can share one boxed object (see {@link DoublePool}).</li>
 * </ul>
 * Lateral acceleration cannot be derived from speed alone (it needs the yaw rate or the path
 * curvature), so a quantised lateral column is kept as it is, with a warning.
 */
final class QuantisedAcceleration {

    static final int MIN_VALUES = 20;
    /** A whole number holding more than this share of the non-empty values is a level. */
    static final double MIN_SHARE_PER_LEVEL = 0.001;
    /** Share of the non-empty values that must lie on the levels. */
    static final double MIN_SHARE_ON_LEVELS = 0.99;
    static final int MAX_DISTINCT_LEVELS = 8;

    /** Half width of the differentiation window in seconds. */
    static final double HALF_WINDOW_S = 0.25;

    /** A longer time step between two samples starts a new segment. */
    static final double MAX_STEP_S = 1.0;

    /** A window shorter than this gives no value. */
    static final double MIN_SPAN_S = 0.1;

    /** Derived values are rounded to this step in g. */
    static final double RESOLUTION_G = 0.001;

    private static final double STEPS_PER_G = 1000;

    private static final double WHOLE_TOLERANCE = 1e-9;

    private QuantisedAcceleration() {
    }

    /**
     * The levels of a quantised column.
     *
     * @param values      the levels in ascending order ({@code -0} counted as 0)
     * @param otherValues number of non-empty values that lie on none of them (outliers)
     */
    record Levels(double[] values, int otherValues) {
    }

    /**
     * The levels of a quantised column, or {@code null} when the column is not quantised (rule in
     * the class comment).
     */
    static Levels quantisedLevels(List<TelemetryPoint> points, Function<TelemetryPoint, Double> channel) {
        // At most 1 % of the values may lie off the levels, so a quantised column never has more
        // distinct whole values than this: beyond it, stop counting.
        int maxDistinct = MAX_DISTINCT_LEVELS + (int) ((1 - MIN_SHARE_ON_LEVELS) * points.size());
        Map<Double, Integer> counts = new HashMap<>();
        int values = 0;
        for (TelemetryPoint p : points) {
            Double v = channel.apply(p);
            if (v == null || !Double.isFinite(v)) {
                continue;
            }
            values++;
            double r = Math.rint(v);
            if (Math.abs(v - r) <= WHOLE_TOLERANCE) {
                counts.merge(r + 0.0, 1, Integer::sum);
                if (counts.size() > maxDistinct) {
                    return null;
                }
            }
        }
        if (values < MIN_VALUES) {
            return null;
        }
        double perLevel = MIN_SHARE_PER_LEVEL * values;
        List<Double> levels = new ArrayList<>();
        int onLevels = 0;
        for (Map.Entry<Double, Integer> e : counts.entrySet()) {
            if (e.getValue() > perLevel) {
                levels.add(e.getKey());
                onLevels += e.getValue();
            }
        }
        if (levels.size() > MAX_DISTINCT_LEVELS || onLevels < MIN_SHARE_ON_LEVELS * values) {
            return null;
        }
        return new Levels(levels.stream().mapToDouble(Double::doubleValue).sorted().toArray(), values - onLevels);
    }

    /**
     * Longitudinal acceleration in g for every sample, derived from timestamp and speed (rule in the
     * class comment); {@code NaN} where it cannot be derived. Already rounded to {@value #RESOLUTION_G} g.
     */
    static double[] longitudinalFromSpeed(List<TelemetryPoint> points) {
        int n = points.size();
        double[] t = new double[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = points.get(i).timestamp();
            v[i] = points.get(i).speed() / 3.6;
        }
        double[] out = new double[n];
        int start = 0;
        while (start < n) {
            int end = start;
            while (end + 1 < n && isContinuation(t[end], t[end + 1])) {
                end++;
            }
            differentiate(t, v, start, end, out);
            start = end + 1;
        }
        return out;
    }

    private static boolean isContinuation(double previous, double next) {
        double dt = next - previous;
        return dt >= 0 && dt <= MAX_STEP_S;
    }

    /** Central difference over ±{@link #HALF_WINDOW_S} within the segment [from, to]. */
    private static void differentiate(double[] t, double[] v, int from, int to, double[] out) {
        int lo = from;
        int hi = from;
        for (int i = from; i <= to; i++) {
            while (lo < i && t[lo] < t[i] - HALF_WINDOW_S) {
                lo++;
            }
            if (hi < i) {
                hi = i;
            }
            while (hi < to && t[hi + 1] <= t[i] + HALF_WINDOW_S) {
                hi++;
            }
            int a = i > from ? Math.min(lo, i - 1) : i;
            int b = i < to ? Math.max(hi, i + 1) : i;
            double span = t[b] - t[a];
            double g = (v[b] - v[a]) / span / ChannelUnits.STANDARD_GRAVITY;
            // + 0.0 turns a rounded -0.0 into 0.0.
            out[i] = span >= MIN_SPAN_S && Double.isFinite(g)
                    ? Math.rint(g * STEPS_PER_G) / STEPS_PER_G + 0.0
                    : Double.NaN;
        }
    }
}
