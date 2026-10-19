package com.telemetry.analyzer.analysis;

/**
 * Two laps resampled onto the same distance grid (reference lap distance).
 *
 * <p>{@code deltaTime[k]} is the time the compare lap needed to reach {@code distance[k]} minus the
 * time the reference lap needed: positive values mean the compare lap is behind. Its slope shows
 * where time is gained or lost; with distance normalisation its last value equals the lap time
 * difference.
 */
public record AlignedLapComparison(
        String referenceLapId,
        String compareLapId,
        double[] distance,
        double[] referenceSpeed,
        double[] compareSpeed,
        double[] referenceThrottle,
        double[] compareThrottle,
        double[] referenceBrake,
        double[] compareBrake,
        double[] deltaTime,
        double referenceLapTime,
        double compareLapTime,
        double referenceLapLength,
        double compareLapLength,
        boolean distanceNormalized,
        boolean distanceFromChannel
) {

    public double finalDelta() {
        return deltaTime.length == 0 ? 0 : deltaTime[deltaTime.length - 1];
    }

    public double lapTimeDelta() {
        return compareLapTime - referenceLapTime;
    }

    public int size() {
        return distance.length;
    }
}
