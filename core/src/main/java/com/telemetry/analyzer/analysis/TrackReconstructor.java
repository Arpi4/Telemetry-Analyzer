package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.TelemetryPoint;

import java.util.List;

/**
 * Builds a 2D outline of the driven line for the track map.
 *
 * <ul>
 *   <li>With GPS: equirectangular projection around the first fix (metres; fine at circuit scale).</li>
 *   <li>Without GPS but with yaw rate: dead reckoning. Heading is the integral of yaw rate, position
 *       the integral of the distance increments along that heading. Gyro bias makes a closed lap
 *       end with a heading error and a gap; on a closed loop both residuals are removed by
 *       distributing them linearly over lap distance.</li>
 * </ul>
 */
public class TrackReconstructor {

    private static final double EARTH_RADIUS_M = 6_371_000;
    private static final double MIN_CHANNEL_COVERAGE = 0.8;

    public enum Source { GPS, YAW_RATE, NONE }

    /**
     * @param headingClosureDeg heading residual of the raw integration against a whole number of turns
     * @param positionClosureM  distance between start and end point before the gap was distributed
     */
    public record TrackOutline(Source source, double[] distance, double[] x, double[] y,
                               double headingClosureDeg, double positionClosureM) {

        public boolean isEmpty() {
            return source == Source.NONE;
        }
    }

    public TrackOutline reconstruct(LapData lap) {
        return reconstruct(lap, LapTrace.of(lap));
    }

    public TrackOutline reconstruct(LapData lap, LapTrace trace) {
        return reconstruct(lap, trace, true);
    }

    /**
     * @param correctClosure distribute heading and position residuals of a closed lap; false
     *                       returns the raw dead-reckoning result (used to illustrate the drift)
     */
    public TrackOutline reconstruct(LapData lap, LapTrace trace, boolean correctClosure) {
        List<TelemetryPoint> points = lap.points();
        int n = points.size();
        if (n < 3) {
            return empty(trace);
        }
        long gps = points.stream().filter(p -> p.latitude() != null && p.longitude() != null).count();
        if ((double) gps / n >= MIN_CHANNEL_COVERAGE) {
            return fromGps(points, trace);
        }
        long yaw = points.stream().filter(p -> p.yawRate() != null).count();
        if ((double) yaw / n >= MIN_CHANNEL_COVERAGE) {
            return fromYawRate(points, trace, correctClosure);
        }
        return empty(trace);
    }

    private static TrackOutline fromGps(List<TelemetryPoint> points, LapTrace trace) {
        int n = points.size();
        TelemetryPoint origin = points.stream().filter(p -> p.latitude() != null && p.longitude() != null)
                .findFirst().orElseThrow();
        double lat0 = Math.toRadians(origin.latitude());
        double lon0 = Math.toRadians(origin.longitude());
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            TelemetryPoint p = points.get(i);
            if (p.latitude() == null || p.longitude() == null) {
                x[i] = i > 0 ? x[i - 1] : 0;
                y[i] = i > 0 ? y[i - 1] : 0;
                continue;
            }
            x[i] = EARTH_RADIUS_M * (Math.toRadians(p.longitude()) - lon0) * Math.cos(lat0);
            y[i] = EARTH_RADIUS_M * (Math.toRadians(p.latitude()) - lat0);
        }
        double gap = Math.hypot(x[n - 1] - x[0], y[n - 1] - y[0]);
        return new TrackOutline(Source.GPS, trace.distance(), x, y, 0, gap);
    }

    private static TrackOutline fromYawRate(List<TelemetryPoint> points, LapTrace trace, boolean correctClosure) {
        int n = points.size();
        double[] s = trace.distance();
        double[] t = trace.elapsed();
        double length = trace.lapLength();

        double[] heading = new double[n];
        for (int i = 1; i < n; i++) {
            double w0 = Math.toRadians(yawOrZero(points.get(i - 1)));
            double w1 = Math.toRadians(yawOrZero(points.get(i)));
            heading[i] = heading[i - 1] + 0.5 * (w0 + w1) * Math.max(0, t[i] - t[i - 1]);
        }

        // A closed lap turns by a whole number of revolutions (±1 on a normal circuit).
        double turns = Math.round(heading[n - 1] / (2 * Math.PI));
        boolean closedLoop = correctClosure && turns != 0 && length > 0;
        double headingResidual = heading[n - 1] - turns * 2 * Math.PI;
        if (closedLoop) {
            for (int i = 0; i < n; i++) {
                heading[i] -= headingResidual * s[i] / length;
            }
        }

        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 1; i < n; i++) {
            double ds = s[i] - s[i - 1];
            double h = 0.5 * (heading[i - 1] + heading[i]);
            x[i] = x[i - 1] + ds * Math.cos(h);
            y[i] = y[i - 1] + ds * Math.sin(h);
        }
        double gapX = x[n - 1] - x[0];
        double gapY = y[n - 1] - y[0];
        double gap = Math.hypot(gapX, gapY);
        if (closedLoop) {
            for (int i = 0; i < n; i++) {
                double f = s[i] / length;
                x[i] -= gapX * f;
                y[i] -= gapY * f;
            }
        }
        return new TrackOutline(Source.YAW_RATE, s, x, y, Math.toDegrees(headingResidual), gap);
    }

    private static double yawOrZero(TelemetryPoint p) {
        return p.yawRate() == null ? 0 : p.yawRate();
    }

    private static TrackOutline empty(LapTrace trace) {
        return new TrackOutline(Source.NONE, trace.distance(), new double[0], new double[0], 0, 0);
    }
}
