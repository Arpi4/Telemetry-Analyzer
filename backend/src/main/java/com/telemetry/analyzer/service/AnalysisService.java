package com.telemetry.analyzer.service;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalysisService {

    public LapSummary summarizeLap(LapData lap) {
        if (lap.points().isEmpty()) {
            return new LapSummary(0, 0, 0, 0, 0, 0);
        }

        List<TelemetryPoint> points = lap.points();
        double minSpeed = points.stream().mapToDouble(TelemetryPoint::speed).min().orElse(0);
        double maxSpeed = points.stream().mapToDouble(TelemetryPoint::speed).max().orElse(0);
        double avgSpeed = points.stream().mapToDouble(TelemetryPoint::speed).average().orElse(0);

        long throttleSamples = points.stream().filter(p -> p.throttle() > 5.0).count();
        long brakeSamples = points.stream().filter(p -> p.brake() > 5.0).count();
        double throttleRatio = 100.0 * throttleSamples / points.size();
        double brakeRatio = 100.0 * brakeSamples / points.size();

        int gearChanges = 0;
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).gear() != points.get(i - 1).gear()) {
                gearChanges++;
            }
        }

        return new LapSummary(minSpeed, maxSpeed, avgSpeed, throttleRatio, brakeRatio, gearChanges);
    }

    public LapDelta compareLaps(LapData reference, LapData compare) {
        int size = Math.min(reference.points().size(), compare.points().size());
        double avgSpeedDelta = 0;
        for (int i = 0; i < size; i++) {
            avgSpeedDelta += compare.points().get(i).speed() - reference.points().get(i).speed();
        }
        if (size > 0) {
            avgSpeedDelta /= size;
        }
        return new LapDelta(size, avgSpeedDelta);
    }

    public record LapSummary(double minSpeed, double maxSpeed, double avgSpeed,
                             double throttleUsagePercent, double brakeUsagePercent, int gearChanges) {}

    public record LapDelta(int comparedSamples, double avgSpeedDelta) {}
}
