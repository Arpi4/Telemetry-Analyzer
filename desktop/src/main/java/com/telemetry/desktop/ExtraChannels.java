package com.telemetry.desktop;

import com.telemetry.analyzer.analysis.LapTrace;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.TelemetryPoint;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Optional channels of one lap as arrays index-aligned with its {@link LapTrace}: steering angle
 * (deg), lateral and longitudinal acceleration (g) and engine speed (1/min). A channel is
 * {@code null} when the lap does not carry it (fewer than two samples with a value). Samples
 * missing inside a carried channel are bridged linearly over lap distance, and the ends hold the
 * first / last value, so the arrays can be plotted and read like the trace's own channels.
 */
record ExtraChannels(double[] steering, double[] lateralG, double[] longitudinalG, double[] rpm) {

    /** Fewest samples with a value for a lap to count as carrying the channel. */
    static final int MIN_SAMPLES = 2;

    static ExtraChannels of(LapData lap, LapTrace trace) {
        List<TelemetryPoint> points = lap.points();
        double[] distance = trace.distance();
        return new ExtraChannels(
                channel(points, distance, TelemetryPoint::steeringAngle),
                channel(points, distance, TelemetryPoint::lateralAcceleration),
                channel(points, distance, TelemetryPoint::longitudinalAcceleration),
                channel(points, distance, TelemetryPoint::engineRpm));
    }

    boolean hasAccelerations() {
        return lateralG != null || longitudinalG != null;
    }

    private static double[] channel(List<TelemetryPoint> points, double[] distance,
                                    Function<TelemetryPoint, Double> channel) {
        int n = Math.min(points.size(), distance.length);
        double[] out = new double[n];
        int last = -1; // last sample with a value
        int count = 0;
        for (int i = 0; i < n; i++) {
            Double v = channel.apply(points.get(i));
            if (v == null || !Double.isFinite(v)) {
                continue;
            }
            out[i] = v;
            if (last < 0) {
                Arrays.fill(out, 0, i, v);
            } else if (i - last > 1) {
                bridge(out, distance, last, i);
            }
            last = i;
            count++;
        }
        if (count < MIN_SAMPLES) {
            return null;
        }
        Arrays.fill(out, last + 1, n, out[last]);
        return out;
    }

    /** Linear in lap distance between samples a and b; by sample index where the car stands still. */
    private static void bridge(double[] values, double[] distance, int a, int b) {
        double span = distance[b] - distance[a];
        for (int k = a + 1; k < b; k++) {
            double f = span > 0 ? (distance[k] - distance[a]) / span : (double) (k - a) / (b - a);
            values[k] = values[a] + f * (values[b] - values[a]);
        }
    }
}
