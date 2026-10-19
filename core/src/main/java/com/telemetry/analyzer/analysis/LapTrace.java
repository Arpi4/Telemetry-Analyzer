package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.TelemetryPoint;

import java.util.List;

/**
 * Column-oriented view of one lap: every channel as a primitive array, plus lap distance and
 * elapsed time measured from the first sample. All analysis algorithms work on this view.
 *
 * <p>Lap distance comes from the Distance channel when at least {@link #MIN_DISTANCE_COVERAGE}
 * of the samples carry it; otherwise it is reconstructed by trapezoidal integration of speed
 * over time. Either way it is forced to be non-decreasing, so it can be used as an
 * interpolation axis.
 */
public record LapTrace(
        String lapId,
        double[] distance,
        double[] elapsed,
        double[] speed,
        double[] throttle,
        double[] brake,
        double[] gear,
        boolean distanceFromChannel
) {

    public static final double MIN_DISTANCE_COVERAGE = 0.8;

    /** Gaps longer than this (logger pause, merged files) do not contribute integrated distance. */
    private static final double MAX_INTEGRATION_STEP_S = 1.0;

    /** Below this mean speed a distance plateau is treated as real standstill. */
    private static final double MOVING_SPEED_KMH = 2;

    public static LapTrace of(LapData lap) {
        List<TelemetryPoint> points = lap.points();
        int n = points.size();
        double[] elapsed = new double[n];
        double[] speed = new double[n];
        double[] throttle = new double[n];
        double[] brake = new double[n];
        double[] gear = new double[n];
        double t0 = n == 0 ? 0 : points.getFirst().timestamp();
        int withDistance = 0;
        for (int i = 0; i < n; i++) {
            TelemetryPoint p = points.get(i);
            elapsed[i] = p.timestamp() - t0;
            speed[i] = p.speed();
            throttle[i] = p.throttle();
            brake[i] = p.brake();
            gear[i] = p.gear();
            if (p.distanceMeters() != null) {
                withDistance++;
            }
        }
        boolean fromChannel = n > 0 && (double) withDistance / n >= MIN_DISTANCE_COVERAGE;
        double[] distance = fromChannel ? channelDistance(points, speed, elapsed) : integratedDistance(speed, elapsed);
        return new LapTrace(lap.lapId(), distance, elapsed, speed, throttle, brake, gear, fromChannel);
    }

    public int size() {
        return distance.length;
    }

    public double lapLength() {
        return size() == 0 ? 0 : distance[size() - 1];
    }

    public double lapTime() {
        return size() == 0 ? 0 : elapsed[size() - 1];
    }

    /**
     * s_i = s_{i-1} + (v_{i-1} + v_i) / 2 * (t_i - t_{i-1}), speed converted from km/h to m/s.
     */
    static double[] integratedDistance(double[] speedKmh, double[] elapsed) {
        int n = speedKmh.length;
        double[] d = new double[n];
        for (int i = 1; i < n; i++) {
            double dt = elapsed[i] - elapsed[i - 1];
            double step = (dt > 0 && dt <= MAX_INTEGRATION_STEP_S)
                    ? 0.5 * (speedKmh[i - 1] + speedKmh[i]) / 3.6 * dt
                    : 0;
            d[i] = d[i - 1] + Math.max(0, step);
        }
        return d;
    }

    /** Missing samples are bridged by integration; small backwards jitter is clamped away. */
    private static double[] channelDistance(List<TelemetryPoint> points, double[] speedKmh, double[] elapsed) {
        int n = points.size();
        double[] d = new double[n];
        double origin = Double.NaN;
        for (TelemetryPoint p : points) {
            if (p.distanceMeters() != null) {
                origin = p.distanceMeters();
                break;
            }
        }
        for (int i = 0; i < n; i++) {
            Double raw = points.get(i).distanceMeters();
            double value;
            if (raw != null) {
                value = raw - origin;
            } else if (i == 0) {
                value = 0;
            } else {
                double dt = Math.max(0, elapsed[i] - elapsed[i - 1]);
                value = d[i - 1] + 0.5 * (speedKmh[i - 1] + speedKmh[i]) / 3.6 * dt;
            }
            d[i] = i == 0 ? Math.max(0, value) : Math.max(d[i - 1], value);
        }
        return dequantize(d, speedKmh, elapsed);
    }

    /**
     * Exported Distance channels are often rounded (MoTeC CSV from ACC: whole metres), which at
     * 200 Hz gives plateaus of several identical samples and a stair-shaped time-vs-distance
     * curve. While the car is moving, samples between two value changes are re-distributed
     * linearly in time; real standstill (pit box) keeps its plateau.
     */
    static double[] dequantize(double[] d, double[] speedKmh, double[] elapsed) {
        int n = d.length;
        double[] out = d.clone();
        int anchor = 0;
        for (int i = 1; i < n; i++) {
            if (!(d[i] > d[i - 1])) {
                continue;
            }
            if (i - anchor > 1 && meanSpeed(speedKmh, anchor, i) >= MOVING_SPEED_KMH) {
                double t0 = elapsed[anchor];
                double t1 = elapsed[i];
                for (int k = anchor + 1; k < i; k++) {
                    out[k] = t1 > t0 ? d[anchor] + (d[i] - d[anchor]) * (elapsed[k] - t0) / (t1 - t0) : d[anchor];
                }
            }
            anchor = i;
        }
        // Trailing plateau has no next value change to interpolate towards; without this the lap
        // would "reach" its final distance a few samples before its last timestamp.
        if (anchor < n - 1 && meanSpeed(speedKmh, anchor, n - 1) >= MOVING_SPEED_KMH) {
            for (int k = anchor + 1; k < n; k++) {
                double dt = Math.max(0, elapsed[k] - elapsed[k - 1]);
                out[k] = out[k - 1] + 0.5 * (speedKmh[k - 1] + speedKmh[k]) / 3.6 * dt;
            }
        }
        return out;
    }

    private static double meanSpeed(double[] speedKmh, int from, int to) {
        double sum = 0;
        for (int k = from; k <= to; k++) {
            sum += speedKmh[k];
        }
        return sum / (to - from + 1);
    }
}
