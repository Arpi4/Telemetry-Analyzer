package com.telemetry.analyzer.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TelemetryControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private String sessionId;

    /**
     * MoTeC-style export: three 1000 m laps at 50 Hz. Lap 2 is 10 km/h slower on its second half,
     * with braking from 600 m to 700 m on every lap.
     */
    private static String motecCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("\"Format\",\"MoTeC CSV File\"\n");
        StringBuilder beacons = new StringBuilder();
        StringBuilder rows = new StringBuilder();
        double t = 0;
        double outing = 0;
        for (int lap = 1; lap <= 3; lap++) {
            double s = 0;
            while (s < 1000) {
                double kmh = s >= 600 && s < 700 ? 100 : (lap == 2 && s >= 500 ? 150 : 160);
                double brake = s >= 600 && s < 700 ? 80 : 0;
                double throttle = brake > 0 ? 0 : 100;
                rows.append(String.format(Locale.ROOT, "\"%.3f\",\"%.2f\",\"%.1f\",\"%.0f\",\"%.0f\",\"%d\"%n",
                        t, outing + s, kmh, throttle, brake, brake > 0 ? 3 : 5));
                s += kmh / 3.6 * 0.02;
                t += 0.02;
            }
            outing += 1000;
            if (lap < 3) {
                beacons.append(String.format(Locale.ROOT, "%.3f ", t));
            }
        }
        sb.append("\"Beacon Markers\",\"").append(beacons).append("\"\n\n\n");
        sb.append("\"Time\",\"Distance\",\"SPEED\",\"THROTTLE\",\"BRAKE\",\"GEAR\"\n");
        sb.append("\"s\",\"m\",\"km/h\",\"%\",\"%\",\"no\"\n");
        sb.append(rows);
        return sb.toString();
    }

    @BeforeEach
    void upload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "motec_export.csv", "text/csv",
                motecCsv().getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(multipart("/api/telemetry/import").file(file))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode res = json.readTree(body);
        assertEquals(3, res.get("laps").asInt());
        sessionId = res.get("sessionId").asText();
    }

    private JsonNode getJson(String url) throws Exception {
        String body = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void existingEndpointsKeepTheirShape() throws Exception {
        JsonNode sessions = getJson("/api/sessions");
        assertTrue(sessions.isArray() && !sessions.isEmpty());

        JsonNode summary = getJson("/api/sessions/" + sessionId + "/laps/lap-1/summary");
        assertEquals(160, summary.get("maxSpeed").asDouble(), 1e-9);

        JsonNode compare = getJson("/api/compare?referenceSessionId=" + sessionId + "&compareSessionId=" + sessionId
                + "&referenceLapId=lap-1&compareLapId=lap-2");
        assertTrue(compare.get("delta").has("avgSpeedDelta"));

        JsonNode segments = getJson("/api/compare/segments?referenceSessionId=" + sessionId
                + "&compareSessionId=" + sessionId + "&lapId=lap-1");
        assertEquals(10, segments.get("segments").size());
    }

    @Test
    void overviewFindsFastestLap() throws Exception {
        JsonNode overview = getJson("/api/sessions/" + sessionId + "/overview");
        assertEquals(3, overview.get("laps").size());
        assertTrue(overview.get("laps").get(0).get("complete").asBoolean());
        String fastest = overview.get("fastestLapId").asText();
        assertTrue(fastest.equals("lap-1") || fastest.equals("lap-3"), fastest);
        assertTrue(overview.get("theoreticalBest").get("lapTime").asDouble() > 0);
    }

    @Test
    void eventsReportTheBrakingZone() throws Exception {
        JsonNode events = getJson("/api/sessions/" + sessionId + "/laps/lap-1/events");
        assertEquals(1, events.get("brakingZones").size());
        assertEquals(600, events.get("brakingZones").get(0).get("startDistance").asDouble(), 2);
        assertEquals(1, events.get("corners").size());
    }

    @Test
    void alignedCompareMatchesLapTimeDelta() throws Exception {
        JsonNode res = getJson("/api/compare/aligned?referenceSessionId=" + sessionId + "&referenceLapId=lap-1"
                + "&compareSessionId=" + sessionId + "&compareLapId=lap-2&stepMeters=5");
        JsonNode c = res.get("comparison");
        double lapTimeDelta = c.get("compareLapTime").asDouble() - c.get("referenceLapTime").asDouble();
        // 400 m at 150 instead of 160 km/h (the braking zone is identical): 0.6 s
        assertEquals(0.6, lapTimeDelta, 0.05);
        assertEquals(lapTimeDelta, res.get("finalDelta").asDouble(), 0.03);
        assertEquals(10, res.get("sectors").size());
        assertEquals(1, res.get("brakingPoints").size());
    }

    @Test
    void alignedCompareRejectsUnknownLap() throws Exception {
        mvc.perform(get("/api/compare/aligned?referenceSessionId=" + sessionId + "&referenceLapId=lap-9"
                        + "&compareSessionId=" + sessionId + "&compareLapId=lap-1"))
                .andExpect(status().isBadRequest());
    }
}
