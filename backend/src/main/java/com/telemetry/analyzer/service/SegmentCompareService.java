package com.telemetry.analyzer.service;

import com.telemetry.analyzer.api.dto.SegmentDeltaDto;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SegmentCompareService {

    private static final double MIN_KMH = 1.0;

    public List<SegmentDeltaDto> compareLapSegments(LapData reference, LapData compare, int segmentCount) {
        int nSeg = Math.clamp(segmentCount, 4, 16);
        List<TelemetryPoint> rp = reference.points();
        List<TelemetryPoint> cp = compare.points();
        if (rp.isEmpty() || cp.isEmpty()) {
            return List.of();
        }
        if (distanceCoverage(rp) >= 0.8 && distanceCoverage(cp) >= 0.8) {
            return byDistance(rp, cp, nSeg);
        }
        return byIndex(rp, cp, nSeg);
    }

    private static double distanceCoverage(List<TelemetryPoint> points) {
        if (points.isEmpty()) {
            return 0;
        }
        long ok = points.stream().filter(p -> p.distanceMeters() != null).count();
        return (double) ok / (double) points.size();
    }

    private static List<SegmentDeltaDto> byDistance(List<TelemetryPoint> rp, List<TelemetryPoint> cp, int nSeg) {
        double maxR = maxDistance(rp);
        double maxC = maxDistance(cp);
        double maxD = Math.min(maxR, maxC);
        if (maxD <= 1) {
            return byIndex(rp, cp, nSeg);
        }
        double width = maxD / nSeg;
        List<SegmentDeltaDto> out = new ArrayList<>();
        for (int k = 0; k < nSeg; k++) {
            double start = k * width;
            double end = (k == nSeg - 1) ? maxD : (k + 1) * width;
            double ar = avgSpeedInDistanceRange(rp, start, end);
            double ac = avgSpeedInDistanceRange(cp, start, end);
            double len = end - start;
            double dt = deltaSecondsForSegment(len, ar, ac);
            double p0 = start / maxD;
            double p1 = end / maxD;
            out.add(new SegmentDeltaDto(k + 1, p0, p1, start, end, ar, ac, dt, true));
        }
        return out;
    }

    private static List<SegmentDeltaDto> byIndex(List<TelemetryPoint> rp, List<TelemetryPoint> cp, int nSeg) {
        int n = Math.min(rp.size(), cp.size());
        if (n < nSeg) {
            nSeg = Math.max(2, n / 2);
        }
        List<SegmentDeltaDto> out = new ArrayList<>();
        for (int k = 0; k < nSeg; k++) {
            int i0 = k * n / nSeg;
            int i1 = (k + 1) * n / nSeg;
            if (i1 <= i0) {
                i1 = Math.min(n, i0 + 1);
            }
            double ar = avgSpeedIndexSlice(rp, i0, i1);
            double ac = avgSpeedIndexSlice(cp, i0, i1);
            double p0 = (double) i0 / (double) n;
            double p1 = (double) i1 / (double) n;
            double estDist = 50.0 * (p1 - p0);
            double dt = deltaSecondsForSegment(estDist, ar, ac);
            out.add(new SegmentDeltaDto(k + 1, p0, p1, null, null, ar, ac, dt, false));
        }
        return out;
    }

    private static double maxDistance(List<TelemetryPoint> points) {
        double m = 0;
        for (TelemetryPoint p : points) {
            if (p.distanceMeters() != null) {
                m = Math.max(m, p.distanceMeters());
            }
        }
        return m;
    }

    private static double avgSpeedInDistanceRange(List<TelemetryPoint> points, double start, double end) {
        double sum = 0;
        int c = 0;
        for (TelemetryPoint p : points) {
            Double d = p.distanceMeters();
            if (d == null) {
                continue;
            }
            if (d < start || d > end) {
                continue;
            }
            sum += p.speed();
            c++;
        }
        return c == 0 ? MIN_KMH : sum / c;
    }

    private static double avgSpeedIndexSlice(List<TelemetryPoint> points, int i0, int i1) {
        double sum = 0;
        int c = 0;
        for (int i = i0; i < i1 && i < points.size(); i++) {
            sum += points.get(i).speed();
            c++;
        }
        return c == 0 ? MIN_KMH : sum / c;
    }

    /**
     * Positive delta: compare lap slower in this segment (more time at similar distance).
     */
    private static double deltaSecondsForSegment(double distanceM, double refKmh, double cmpKmh) {
        if (refKmh < MIN_KMH || cmpKmh < MIN_KMH) {
            return 0;
        }
        double vRef = refKmh / 3.6;
        double vCmp = cmpKmh / 3.6;
        return distanceM * (1.0 / vCmp - 1.0 / vRef);
    }
}
