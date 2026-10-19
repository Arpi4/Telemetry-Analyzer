package com.telemetry.desktop;

import com.telemetry.analyzer.analysis.DrivingEvents;
import com.telemetry.analyzer.analysis.LapTrace;
import com.telemetry.analyzer.analysis.SessionLaps;
import com.telemetry.analyzer.analysis.TrackReconstructor.TrackOutline;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.service.AnalyzerServices;
import com.telemetry.analyzer.service.TelemetryImportService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Desktop-side state: the core services, the imported sessions and a per-lap cache of analysis
 * results. Everything except {@link #importFile(Path)} must be called on the FX thread.
 */
final class SessionModel {

    record LapAnalysis(SessionData session, LapData lap, LapTrace trace, DrivingEvents events,
                       TrackOutline outline, boolean complete, boolean fastest) {
    }

    private final AnalyzerServices services = new AnalyzerServices();
    private final ObservableList<SessionData> sessions = FXCollections.observableArrayList();
    private final Map<LapData, LapAnalysis> cache = new IdentityHashMap<>();
    private final Map<SessionData, List<LapData>> completeLaps = new IdentityHashMap<>();
    private final Map<SessionData, LapData> fastest = new IdentityHashMap<>();

    AnalyzerServices services() {
        return services;
    }

    ObservableList<SessionData> sessions() {
        return sessions;
    }

    /** Safe to call from a background thread: parsing only touches the thread-safe session store. */
    TelemetryImportService.ImportResult importFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return services.importService().importFile(bytes, file.getFileName().toString());
    }

    /** Re-reads the store; sessions are listed in import order. */
    void refresh() {
        List<SessionData> all = new ArrayList<>(services.sessionStore().list());
        all.sort(Comparator.comparing(SessionData::importedAt));
        sessions.setAll(all);
    }

    List<LapData> completeLaps(SessionData session) {
        return completeLaps.computeIfAbsent(session, SessionLaps::completeLaps);
    }

    Optional<LapData> fastestLap(SessionData session) {
        return Optional.ofNullable(fastest.computeIfAbsent(session, x -> SessionLaps.fastestLap(x).orElse(null)));
    }

    /** Complete laps of the session, fastest first. */
    List<LapData> lapsByTime(SessionData session) {
        List<LapData> laps = new ArrayList<>(completeLaps(session));
        laps.sort(Comparator.comparingDouble(l -> analyze(session, l).trace().lapTime()));
        return laps;
    }

    LapAnalysis analyze(SessionData session, LapData lap) {
        return cache.computeIfAbsent(lap, l -> {
            LapTrace trace = LapTrace.of(l);
            DrivingEvents events = services.drivingEventDetector().detect(trace);
            TrackOutline outline = services.trackReconstructor().reconstruct(l, trace);
            boolean complete = completeLaps(session).contains(l);
            boolean isFastest = fastestLap(session).map(f -> f == l).orElse(false);
            return new LapAnalysis(session, l, trace, events, outline, complete, isFastest);
        });
    }

    SessionData sessionOf(LapData lap) {
        for (SessionData s : sessions) {
            if (s.laps().contains(lap)) {
                return s;
            }
        }
        return null;
    }
}
