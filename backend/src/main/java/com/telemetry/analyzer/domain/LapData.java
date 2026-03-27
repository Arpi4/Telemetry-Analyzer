package com.telemetry.analyzer.domain;

import java.util.List;

public record LapData(
        String lapId,
        List<TelemetryPoint> points
) {
}
