package com.telemetry.analyzer.analysis;

import java.util.List;

/**
 * Result types of {@link DrivingEventDetector}. Distances are lap distance in metres, times are
 * seconds from the lap start, speeds km/h, pedal values percent.
 */
public record DrivingEvents(
        String lapId,
        List<BrakingZone> brakingZones,
        List<CornerApex> corners,
        DrivingStyleMetrics metrics
) {

    public record BrakingZone(
            int index,
            double startDistance,
            double endDistance,
            double startTime,
            double duration,
            double entrySpeed,
            double minSpeed,
            double peakBrake
    ) {
        public double speedDrop() {
            return entrySpeed - minSpeed;
        }
    }

    public record CornerApex(
            int index,
            double distance,
            double minSpeed,
            double entrySpeed,
            double exitSpeed,
            int gear
    ) {
    }

    /** Shares are time-weighted percentages of the lap time. */
    public record DrivingStyleMetrics(
            double lapTime,
            double lapLength,
            double averageSpeed,
            double maxSpeed,
            double fullThrottlePercent,
            double brakingPercent,
            double coastingPercent,
            double pedalOverlapPercent,
            int gearChanges
    ) {
    }

    /**
     * One braking zone of the reference lap matched with the nearest zone of the compare lap.
     * Positive {@code startDeltaMeters}: the compare lap starts braking later (deeper).
     */
    public record BrakingPointDelta(
            int referenceZone,
            int compareZone,
            double referenceStart,
            double compareStart,
            double startDeltaMeters,
            double minSpeedDelta,
            double durationDelta
    ) {
    }
}
