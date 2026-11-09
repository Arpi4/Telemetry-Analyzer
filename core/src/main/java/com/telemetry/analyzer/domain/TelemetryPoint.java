package com.telemetry.analyzer.domain;

/**
 * One telemetry sample. Required channels are primitives; optional ones are nullable.
 *
 * @param yawRate                  yaw rate in deg/s (positive = counter-clockwise), used to reconstruct
 *                                 the track outline when no GPS is logged
 * @param lateralAcceleration      lateral acceleration in g (MoTeC/ACC "G_LAT")
 * @param longitudinalAcceleration longitudinal acceleration in g (MoTeC/ACC "G_LON")
 * @param steeringAngle            steering wheel angle in degrees (MoTeC/ACC "STEERANGLE")
 * @param engineRpm                engine speed in 1/min (MoTeC/ACC "RPMS", OpenF1 "rpm")
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
        Double yawRate,
        Double lateralAcceleration,
        Double longitudinalAcceleration,
        Double steeringAngle,
        Double engineRpm
) {

    public TelemetryPoint(double timestamp, double speed, double throttle, double brake, int gear,
                          Double latitude, Double longitude, Double distanceMeters) {
        this(timestamp, speed, throttle, brake, gear, latitude, longitude, distanceMeters, null);
    }

    public TelemetryPoint(double timestamp, double speed, double throttle, double brake, int gear,
                          Double latitude, Double longitude, Double distanceMeters, Double yawRate) {
        this(timestamp, speed, throttle, brake, gear, latitude, longitude, distanceMeters, yawRate,
                null, null, null, null);
    }

    public TelemetryPoint withDistance(Double newDistanceMeters) {
        return new TelemetryPoint(timestamp, speed, throttle, brake, gear, latitude, longitude, newDistanceMeters,
                yawRate, lateralAcceleration, longitudinalAcceleration, steeringAngle, engineRpm);
    }

    public TelemetryPoint withAccelerationsAndSteering(Double newLateral, Double newLongitudinal,
                                                      Double newSteeringAngle) {
        return new TelemetryPoint(timestamp, speed, throttle, brake, gear, latitude, longitude, distanceMeters,
                yawRate, newLateral, newLongitudinal, newSteeringAngle, engineRpm);
    }
}
