package com.telemetry.analyzer.domain;

import java.time.Instant;
import java.util.List;

public record SessionData(
        String sessionId,
        String sourceFile,
        Instant importedAt,
        List<LapData> laps
) {
}
