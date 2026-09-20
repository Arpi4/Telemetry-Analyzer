package com.telemetry.analyzer.service;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.parser.TelemetryParser;

import java.util.List;

public class TelemetryImportService {

    private final List<TelemetryParser> parsers;
    private final SessionStore sessionStore;

    public TelemetryImportService(List<TelemetryParser> parsers, SessionStore sessionStore) {
        this.parsers = parsers;
        this.sessionStore = sessionStore;
    }

    public ImportResult importFile(byte[] content, String fileName) {
        String name = (fileName == null || fileName.isBlank()) ? "unknown" : fileName;
        ImportReport report = new ImportReport(name);

        TelemetryParser parser = parsers.stream()
                .filter(p -> p.supports(name))
                .findFirst()
                .orElse(null);

        if (parser == null) {
            report.addError("Unsupported file type: " + name);
            return new ImportResult(null, report);
        }

        SessionData session;
        try {
            session = parser.parse(content, name, report);
        } catch (Throwable t) {
            report.addError("Import failed unexpectedly: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " - " + t.getMessage()));
            return new ImportResult(null, report);
        }
        if (session == null) {
            return new ImportResult(null, report);
        }
        if (!hasTelemetryPoints(session)) {
            report.addError("No telemetry samples were parsed; session was not stored.");
            return new ImportResult(null, report);
        }
        sessionStore.save(session);
        return new ImportResult(session, report);
    }

    private static boolean hasTelemetryPoints(SessionData session) {
        return session.laps().stream().anyMatch(lap -> lap.points() != null && !lap.points().isEmpty());
    }

    public record ImportResult(SessionData session, ImportReport report) {}
}
