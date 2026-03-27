package com.telemetry.analyzer.domain;

public record TelemetryPoint(
        double timestamp,
        double speed,
        double throttle,
        double brake,
        int gear,
        Double latitude,
        Double longitude
) {
}
