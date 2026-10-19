package com.telemetry.analyzer.domain;

/**
 * One telemetry sample. Required channels are primitives; optional ones are nullable.
 *
 * @param yawRate yaw rate in deg/s (positive = counter-clockwise), used to reconstruct the track
 *                outline when no GPS is logged
 */
public record TelemetryPoint(
        double timestamp,
        double speed,
        double throttle,
        double brake,
        int gear,
        Double latitude,
        Double longitude,
        Double distanceMeters,
        Double yawRate
) {

    public TelemetryPoint(double timestamp, double speed, double throttle, double brake, int gear,
                          Double latitude, Double longitude, Double distanceMeters) {
        this(timestamp, speed, throttle, brake, gear, latitude, longitude, distanceMeters, null);
    }

    public TelemetryPoint withDistance(Double newDistanceMeters) {
        return new TelemetryPoint(timestamp, speed, throttle, brake, gear, latitude, longitude, newDistanceMeters, yawRate);
    }
}
