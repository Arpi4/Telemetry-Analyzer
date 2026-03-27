package com.telemetry.analyzer.service;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.parser.TelemetryParser;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
public class TelemetryImportService {

    private final List<TelemetryParser> parsers;
    private final SessionStore sessionStore;

    public TelemetryImportService(List<TelemetryParser> parsers, SessionStore sessionStore) {
        this.parsers = parsers;
        this.sessionStore = sessionStore;
    }

    public ImportResult importFile(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        ImportReport report = new ImportReport(fileName);

        TelemetryParser parser = parsers.stream()
                .filter(p -> p.supports(fileName))
                .findFirst()
                .orElse(null);

        if (parser == null) {
            report.addError("Unsupported file type: " + fileName);
            return new ImportResult(null, report);
        }

        SessionData session = parser.parse(file.getBytes(), fileName, report);
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
