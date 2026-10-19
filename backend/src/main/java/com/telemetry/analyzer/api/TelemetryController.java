package com.telemetry.analyzer.api;

import com.telemetry.analyzer.analysis.AlignedLapComparison;
import com.telemetry.analyzer.analysis.DrivingEventDetector;
import com.telemetry.analyzer.analysis.DrivingEvents;
import com.telemetry.analyzer.analysis.LapComparator;
import com.telemetry.analyzer.analysis.LapTrace;
import com.telemetry.analyzer.analysis.SessionLaps;
import com.telemetry.analyzer.analysis.TrackReconstructor;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.dto.SegmentDeltaDto;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@Validated
@CrossOrigin(origins = "*")
public class TelemetryController {

    private final TelemetryImportService importService;
    private final SessionStore sessionStore;
    private final AnalysisService analysisService;
    private final SegmentCompareService segmentCompareService;
    private final LapComparator lapComparator;
    private final DrivingEventDetector drivingEventDetector;
    private final TrackReconstructor trackReconstructor;

    public TelemetryController(TelemetryImportService importService, SessionStore sessionStore,
                               AnalysisService analysisService, SegmentCompareService segmentCompareService,
                               LapComparator lapComparator, DrivingEventDetector drivingEventDetector,
                               TrackReconstructor trackReconstructor) {
        this.importService = importService;
        this.sessionStore = sessionStore;
        this.analysisService = analysisService;
        this.segmentCompareService = segmentCompareService;
        this.lapComparator = lapComparator;
        this.drivingEventDetector = drivingEventDetector;
        this.trackReconstructor = trackReconstructor;
    }

    @PostMapping("/telemetry/import")
    public ResponseEntity<?> importTelemetry(@RequestParam("file") MultipartFile file) throws IOException {
        TelemetryImportService.ImportResult result = importService.importFile(file.getBytes(), file.getOriginalFilename());
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
    public List<Map<String, Object>> listSessions() {
        return sessionStore.list().stream().map(session -> Map.of(
                "sessionId", session.sessionId(),
                "sourceFile", session.sourceFile(),
                "importedAt", session.importedAt(),
                "lapCount", session.laps().size(),
                "lapIds", session.laps().stream().map(LapData::lapId).collect(Collectors.toList())
        )).toList();
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
                                     @RequestParam(defaultValue = "lap-1") String lapId,
                                     @RequestParam(required = false) String referenceLapId,
                                     @RequestParam(required = false) String compareLapId) {
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

        String resolvedReferenceLapId = (referenceLapId == null || referenceLapId.isBlank()) ? lapId : referenceLapId;
        String resolvedCompareLapId = (compareLapId == null || compareLapId.isBlank()) ? lapId : compareLapId;

        LapData refLap = ref.laps().stream().filter(l -> l.lapId().equals(resolvedReferenceLapId)).findFirst().orElse(null);
        LapData cmpLap = cmp.laps().stream().filter(l -> l.lapId().equals(resolvedCompareLapId)).findFirst().orElse(null);
        if (refLap == null || cmpLap == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Lap not found in one or both sessions.",
                    "referenceLapId", resolvedReferenceLapId,
                    "compareLapId", resolvedCompareLapId,
                    "referenceSessionId", referenceSessionId,
                    "compareSessionId", compareSessionId
            ));
        }
        if (refLap.points().isEmpty() || cmpLap.points().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Cannot compare: one or both laps have no samples.",
                    "referenceLapId", resolvedReferenceLapId,
                    "compareLapId", resolvedCompareLapId
            ));
        }

        return ResponseEntity.ok(Map.of(
                "referenceLapId", resolvedReferenceLapId,
                "compareLapId", resolvedCompareLapId,
                "delta", analysisService.compareLaps(refLap, cmpLap)
        ));
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

    /**
     * Complete laps, fastest lap and theoretical best lap of a session.
     */
    @GetMapping("/sessions/{sessionId}/overview")
    public ResponseEntity<?> sessionOverview(@PathVariable String sessionId,
                                             @RequestParam(defaultValue = "10") int sectorCount) {
        SessionData session = sessionStore.get(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        int sectors = Math.clamp(sectorCount, 2, 50);
        List<Map<String, Object>> laps = session.laps().stream().map(lap -> {
            LapTrace trace = LapTrace.of(lap);
            return Map.<String, Object>of(
                    "lapId", lap.lapId(),
                    "lapTime", trace.lapTime(),
                    "lapLength", trace.lapLength(),
                    "complete", SessionLaps.isComplete(session, lap));
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("laps", laps);
        body.put("fastestLapId", SessionLaps.fastestLap(session).map(LapData::lapId).orElse(null));
        body.put("theoreticalBest", SessionLaps.theoreticalBest(session, sectors).orElse(null));
        return ResponseEntity.ok(body);
    }

    /**
     * Braking zones, corner apexes and pedal usage of one lap.
     */
    @GetMapping("/sessions/{sessionId}/laps/{lapId}/events")
    public ResponseEntity<?> lapEvents(@PathVariable String sessionId, @PathVariable String lapId) {
        LapData lap = findLap(sessionStore.get(sessionId), lapId);
        if (lap == null || lap.points().isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(drivingEventDetector.detect(lap));
    }

    /**
     * 2D outline of the lap (GPS projection, or dead reckoning from yaw rate) for track maps.
     */
    @GetMapping("/sessions/{sessionId}/laps/{lapId}/track")
    public ResponseEntity<?> lapTrack(@PathVariable String sessionId, @PathVariable String lapId) {
        LapData lap = findLap(sessionStore.get(sessionId), lapId);
        if (lap == null || lap.points().isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(trackReconstructor.reconstruct(lap));
    }

    /**
     * Distance-aligned comparison: both laps resampled on one distance grid, cumulative time
     * delta, exact sector deltas and matched braking points.
     */
    @GetMapping("/compare/aligned")
    public ResponseEntity<?> compareAligned(@RequestParam String referenceSessionId,
                                            @RequestParam String referenceLapId,
                                            @RequestParam String compareSessionId,
                                            @RequestParam String compareLapId,
                                            @RequestParam(defaultValue = "2") double stepMeters,
                                            @RequestParam(defaultValue = "10") int sectorCount) {
        LapData refLap = findLap(sessionStore.get(referenceSessionId), referenceLapId);
        LapData cmpLap = findLap(sessionStore.get(compareSessionId), compareLapId);
        if (refLap == null || cmpLap == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Session or lap not found.",
                    "referenceLapId", referenceLapId, "compareLapId", compareLapId));
        }
        AlignedLapComparison comparison;
        try {
            comparison = lapComparator.compare(LapTrace.of(refLap), LapTrace.of(cmpLap),
                    Math.clamp(stepMeters, 1.0, 50.0), true);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        DrivingEvents refEvents = drivingEventDetector.detect(refLap);
        DrivingEvents cmpEvents = drivingEventDetector.detect(cmpLap);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comparison", comparison);
        body.put("finalDelta", comparison.finalDelta());
        body.put("sectors", lapComparator.sectorDeltas(comparison, sectorCount));
        body.put("brakingPoints", drivingEventDetector.compareBrakingPoints(refEvents, cmpEvents));
        return ResponseEntity.ok(body);
    }

    private static LapData findLap(SessionData session, String lapId) {
        if (session == null) {
            return null;
        }
        return session.laps().stream().filter(l -> l.lapId().equals(lapId)).findFirst().orElse(null);
    }
}