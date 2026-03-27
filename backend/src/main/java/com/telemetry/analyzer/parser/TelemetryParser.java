package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.SessionData;

public interface TelemetryParser {
    boolean supports(String fileName);
    SessionData parse(byte[] content, String sourceFile, ImportReport report);
}
