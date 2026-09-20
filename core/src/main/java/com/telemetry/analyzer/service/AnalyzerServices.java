package com.telemetry.analyzer.service;

import com.telemetry.analyzer.parser.CsvTelemetryParser;
import com.telemetry.analyzer.parser.GenericDelimitedParser;
import com.telemetry.analyzer.parser.LdTelemetryParser;
import com.telemetry.analyzer.parser.TelemetryParser;

import java.util.List;

/**
 * Wires parser and analysis objects without a DI container (desktop and tests).
 */
public final class AnalyzerServices {

    private final SessionStore sessionStore;
    private final TelemetryImportService importService;
    private final AnalysisService analysisService;
    private final SegmentCompareService segmentCompareService;

    public AnalyzerServices() {
        this.sessionStore = new SessionStore();
        GenericDelimitedParser generic = new GenericDelimitedParser();
        List<TelemetryParser> parsers = List.of(
                new CsvTelemetryParser(),
                new LdTelemetryParser(generic)
        );
        this.importService = new TelemetryImportService(parsers, sessionStore);
        this.analysisService = new AnalysisService();
        this.segmentCompareService = new SegmentCompareService();
    }

    public SessionStore sessionStore() {
        return sessionStore;
    }

    public TelemetryImportService importService() {
        return importService;
    }

    public AnalysisService analysisService() {
        return analysisService;
    }

    public SegmentCompareService segmentCompareService() {
        return segmentCompareService;
    }
}
