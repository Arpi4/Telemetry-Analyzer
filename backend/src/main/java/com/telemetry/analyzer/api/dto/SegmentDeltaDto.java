package com.telemetry.analyzer.api.dto;

/**
 * Lap segment comparison: progress 0–1 along lap for schematic map placement;
 * distance bounds present when both laps have Distance channel samples.
 */
public record SegmentDeltaDto(
        int index,
        double progressStart,
        double progressEnd,
        Double distanceStartM,
        Double distanceEndM,
        double referenceAvgSpeedKmh,
        double compareAvgSpeedKmh,
        double deltaSeconds,
        boolean distanceBased
) {
}
