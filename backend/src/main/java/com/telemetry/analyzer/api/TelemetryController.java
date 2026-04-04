package com.telemetry.analyzer.api;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.api.dto.SegmentDeltaDto;
import com.telemetry.analyzer.service.AnalysisService;
import com.telemetry.analyzer.service.SegmentCompareService;
import com.telemetry.analyzer.service.SessionStore;
import com.telemetry.analyzer.service.TelemetryImportService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Validated
@CrossOrigin(origins = "*")
public class TelemetryController {

    private final TelemetryImportService importService;
    private final SessionStore sessionStore;
    private final AnalysisService analysisService;
    private final SegmentCompareService segmentCompareService;

    public TelemetryController(TelemetryImportService importService, SessionStore sessionStore, AnalysisService analysisService,
                               SegmentCompareService segmentCompareService) {
        this.importService = importService;
        this.sessionStore = sessionStore;
        this.analysisService = analysisService;
        this.segmentCompareService = segmentCompareService;
    }

    @PostMapping("/telemetry/import")
    public ResponseEntity<?> importTelemetry(@RequestParam("file") MultipartFile file) throws IOException {
        TelemetryImportService.ImportResult result = importService.importFile(file);
        if (result.session() == null) {
            return ResponseEntity.badRequest().body(Map.of("report", result.report()));
        }

        return ResponseEntity.ok(Map.of(
                "sessionId", result.session().sessionId(),
                "sourceFile", result.session().sourceFile(),
                "laps", result.session().laps().size(),
                "report", result.report()
        ));
    }

    @GetMapping("/sessions")
    public List<SessionData> listSessions() {
        return sessionStore.list().stream().toList();
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<?> getSession(@PathVariable @NotBlank String sessionId) {
        SessionData session = sessionStore.get(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(session);
    }

    @GetMapping("/sessions/{sessionId}/laps/{lapId}/summary")
    public ResponseEntity<?> lapSummary(@PathVariable String sessionId, @PathVariable String lapId) {
        SessionData session = sessionStore.get(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        LapData lap = session.laps().stream().filter(l -> l.lapId().equals(lapId)).findFirst().orElse(null);
        if (lap == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(analysisService.summarizeLap(lap));
    }

    @GetMapping("/compare")
    public ResponseEntity<?> compare(@RequestParam String referenceSessionId,
                                     @RequestParam String compareSessionId,
                                     @RequestParam(defaultValue = "lap-1") String lapId) {
        SessionData ref = sessionStore.get(referenceSessionId);
        SessionData cmp = sessionStore.get(compareSessionId);
        if (ref == null || cmp == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "One or both sessions not found."));
        }

        if (ref.laps().isEmpty() || cmp.laps().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Cannot compare: one or both sessions have no lap data.",
                    "referenceSessionId", referenceSessionId,
                    "compareSessionId", compareSessionId
            ));
        }

        LapData refLap = ref.laps().stream().filter(l -> l.lapId().equals(lapId)).findFirst().orElse(null);
        LapData cmpLap = cmp.laps().stream().filter(l -> l.lapId().equals(lapId)).findFirst().orElse(null);
        if (refLap == null || cmpLap == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Lap not found in one or both sessions.",
                    "lapId", lapId,
                    "referenceSessionId", referenceSessionId,
                    "compareSessionId", compareSessionId
            ));
        }
        if (refLap.points().isEmpty() || cmpLap.points().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Cannot compare: one or both laps have no samples.",
                    "lapId", lapId
            ));
        }

        return ResponseEntity.ok(analysisService.compareLaps(refLap, cmpLap));
    }

    /**
     * Split a lap into segments and estimate time delta per segment (compare minus reference).
     * Uses Distance channel when present; otherwise equal index slices.
     */
    @GetMapping("/compare/segments")
    public ResponseEntity<?> compareSegments(@RequestParam String referenceSessionId,
                                             @RequestParam String compareSessionId,
                                             @RequestParam(defaultValue = "lap-1") String lapId,
                                             @RequestParam(defaultValue = "10") int segmentCount) {
        SessionData ref = sessionStore.get(referenceSessionId);
        SessionData cmp = sessionStore.get(compareSessionId);
        if (ref == null || cmp == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "One or both sessions not found."));
        }
        if (ref.laps().isEmpty() || cmp.laps().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot compare: one or both sessions have no lap data."));
        }
        LapData refLap = ref.laps().stream().filter(l -> l.lapId().equals(lapId)).findFirst().orElse(null);
        LapData cmpLap = cmp.laps().stream().filter(l -> l.lapId().equals(lapId)).findFirst().orElse(null);
        if (refLap == null || cmpLap == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Lap not found in one or both sessions.", "lapId", lapId));
        }
        if (refLap.points().isEmpty() || cmpLap.points().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot compare: one or both laps have no samples.", "lapId", lapId));
        }
        List<SegmentDeltaDto> segments = segmentCompareService.compareLapSegments(refLap, cmpLap, segmentCount);
        return ResponseEntity.ok(Map.of("lapId", lapId, "segments", segments));
    }
}
