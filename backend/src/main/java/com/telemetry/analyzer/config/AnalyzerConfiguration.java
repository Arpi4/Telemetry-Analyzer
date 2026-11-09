package com.telemetry.analyzer.config;

import com.telemetry.analyzer.analysis.DrivingEventDetector;
import com.telemetry.analyzer.analysis.LapComparator;
import com.telemetry.analyzer.analysis.TrackReconstructor;
import com.telemetry.analyzer.parser.CsvTelemetryParser;
import com.telemetry.analyzer.parser.GenericDelimitedParser;
import com.telemetry.analyzer.parser.LdTelemetryParser;
import com.telemetry.analyzer.service.AnalysisService;
import com.telemetry.analyzer.service.SegmentCompareService;
import com.telemetry.analyzer.service.SessionStore;
import com.telemetry.analyzer.service.TelemetryImportService;
import com.telemetry.analyzer.parser.TelemetryParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AnalyzerConfiguration {

    /**
     * In-memory session store with a sample budget (telemetry.store.max-samples, 0 = unbounded),
     * so repeated uploads evict the oldest sessions instead of running the heap out.
     */
    @Bean
    public SessionStore sessionStore(@Value("${telemetry.store.max-samples:0}") long maxSamples) {
        return maxSamples > 0 ? new SessionStore(maxSamples) : new SessionStore();
    }

    @Bean
    public GenericDelimitedParser genericDelimitedParser() {
        return new GenericDelimitedParser();
    }

    @Bean
    public CsvTelemetryParser csvTelemetryParser() {
        return new CsvTelemetryParser();
    }

    @Bean
    public LdTelemetryParser ldTelemetryParser(GenericDelimitedParser genericDelimitedParser) {
        return new LdTelemetryParser(genericDelimitedParser);
    }

    @Bean
    public TelemetryImportService telemetryImportService(List<TelemetryParser> parsers, SessionStore sessionStore) {
        return new TelemetryImportService(parsers, sessionStore);
    }

    @Bean
    public AnalysisService analysisService() {
        return new AnalysisService();
    }

    @Bean
    public SegmentCompareService segmentCompareService() {
        return new SegmentCompareService();
    }

    @Bean
    public LapComparator lapComparator() {
        return new LapComparator();
    }

    @Bean
    public DrivingEventDetector drivingEventDetector() {
        return new DrivingEventDetector();
    }

    @Bean
    public TrackReconstructor trackReconstructor() {
        return new TrackReconstructor();
    }
}
