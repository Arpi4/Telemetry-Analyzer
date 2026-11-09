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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        return motecCsv(3, (lap, s) -> s >= 600 && s < 700 ? 100 : (lap == 2 && s >= 500 ? 150 : 160));
    }

    /** Speed in km/h of lap {@code lap} (1-based) at {@code s} metres into the lap. */
    private interface SpeedProfile {
        double kmh(int lap, double s);
    }

    /** {@code lapCount} 1000 m laps at 50 Hz, braking from 600 m to 700 m on every lap. */
    private static String motecCsv(int lapCount, SpeedProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"Format\",\"MoTeC CSV File\"\n");
        StringBuilder beacons = new StringBuilder();
        StringBuilder rows = new StringBuilder();
        double t = 0;
        double outing = 0;
        for (int lap = 1; lap <= lapCount; lap++) {
            double s = 0;
            while (s < 1000) {
                double kmh = profile.kmh(lap, s);
                double brake = s >= 600 && s < 700 ? 80 : 0;
                double throttle = brake > 0 ? 0 : 100;
                rows.append(String.format(Locale.ROOT, "\"%.3f\",\"%.2f\",\"%.1f\",\"%.0f\",\"%.0f\",\"%d\"%n",
                        t, outing + s, kmh, throttle, brake, brake > 0 ? 3 : 5));
                s += kmh / 3.6 * 0.02;
                t += 0.02;
            }
            outing += 1000;
            if (lap < lapCount) {
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
    void overviewReportsLapTimeConsistency() throws Exception {
        JsonNode overview = getJson("/api/sessions/" + sessionId + "/overview");
        assertTrue(overview.has("theoreticalBest"));
        JsonNode c = overview.get("consistency");
        assertEquals(3, c.get("lapCount").asInt());
        assertEquals(3, c.get("lapIds").size());
        // lap 2 is 0.6 s slower than laps 1 and 3: s = sqrt((0.2² + 0.4² + 0.2²) / 2) = 0.346 s
        assertEquals(0.346, c.get("lapTimeStdDev").asDouble(), 0.03);
        assertEquals(0.6, c.get("spread").asDouble(), 0.05);
        assertTrue(c.get("coefficientOfVariationPercent").asDouble() > 0);
        assertTrue(c.get("meanLapTime").asDouble() > c.get("medianLapTime").asDouble());
        assertEquals(10, c.get("sectorStdDevs").size());
        // Lap 2 only differs after 500 m.
        assertTrue(c.get("leastConsistentSector").asInt() >= 6, c.toString());
    }

    @Test
    void overviewConsistencyIsNullWithOneLap() throws Exception {
        String csv = "timestamp,speed,throttle,brake,gear\n0.0,10,20,0,1\n0.1,20,40,0,2\n0.2,30,60,0,3\n";
        MockMultipartFile file = new MockMultipartFile("file", "one_lap.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(multipart("/api/telemetry/import").file(file))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("sessionId").asText();

        JsonNode overview = getJson("/api/sessions/" + id + "/overview");
        assertTrue(overview.has("consistency"));
        assertTrue(overview.get("consistency").isNull());
        assertTrue(overview.get("excludedLaps").isArray() && overview.get("excludedLaps").isEmpty(), overview.toString());
    }

    @Test
    void overviewListsThePitLapsAlsoWithoutConsistency() throws Exception {
        // Two complete laps: lap 1 leaves the pits at 80 km/h, lap 2 is a flying lap. With one
        // flying lap left there are no consistency figures, but the out-lap is still listed.
        String csv = motecCsv(2, (lap, s) -> lap == 1 && s < 150 ? 80 + 80 * s / 150
                : s >= 600 && s < 700 ? 100 : 160);
        MockMultipartFile file = new MockMultipartFile("file", "out_lap.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(multipart("/api/telemetry/import").file(file))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("sessionId").asText();

        JsonNode overview = getJson("/api/sessions/" + id + "/overview");
        overview.get("laps").forEach(lap -> assertTrue(lap.get("complete").asBoolean(), lap.toString()));
        assertTrue(overview.get("consistency").isNull(), overview.toString());
        JsonNode excluded = overview.get("excludedLaps");
        assertEquals(1, excluded.size(), overview.toString());
        assertEquals("lap-1", excluded.get(0).get("lapId").asText());
        assertEquals("out-lap", excluded.get(0).get("reason").asText());
        assertEquals(80, excluded.get(0).get("entrySpeed").asDouble(), 1e-9);
    }

    @Test
    void overviewConsistencyLeavesOutTheOutLapAndTheInLap() throws Exception {
        // lap 1 leaves the pits at 50 km/h, lap 4 enters them at 55 km/h from 850 m; lap 3 is
        // 0.6 s slower than lap 2 on its second half. All four laps are complete.
        String csv = motecCsv(4, (lap, s) -> {
            if (lap == 1 && s < 150) {
                return 50 + 110 * s / 150;
            }
            if (lap == 4 && s >= 850) {
                return 55;
            }
            return s >= 600 && s < 700 ? 100 : (lap == 3 && s >= 500 ? 150 : 160);
        });
        MockMultipartFile file = new MockMultipartFile("file", "pit_laps.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(multipart("/api/telemetry/import").file(file))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("sessionId").asText();

        JsonNode overview = getJson("/api/sessions/" + id + "/overview");
        overview.get("laps").forEach(lap -> assertTrue(lap.get("complete").asBoolean(), lap.toString()));
        // The fastest lap still comes from all complete laps.
        assertEquals("lap-2", overview.get("fastestLapId").asText());

        JsonNode c = overview.get("consistency");
        assertEquals(2, c.get("lapCount").asInt(), c.toString());
        assertEquals("[\"lap-2\",\"lap-3\"]", c.get("lapIds").toString());
        assertEquals(0.6, c.get("spread").asDouble(), 0.05);
        JsonNode excluded = c.get("excludedLaps");
        assertEquals(2, excluded.size(), c.toString());
        assertEquals("lap-1", excluded.get(0).get("lapId").asText());
        assertEquals("out-lap", excluded.get(0).get("reason").asText());
        assertEquals(50, excluded.get(0).get("entrySpeed").asDouble(), 1e-9);
        assertEquals("lap-4", excluded.get(1).get("lapId").asText());
        assertEquals("in-lap", excluded.get(1).get("reason").asText());
        assertEquals(55, excluded.get(1).get("exitSpeed").asDouble(), 1e-9);
        // The top-level list is the same.
        assertEquals(excluded, overview.get("excludedLaps"));

        // Without pit laps the lists are empty, not missing.
        JsonNode plainOverview = getJson("/api/sessions/" + sessionId + "/overview");
        JsonNode plain = plainOverview.get("consistency");
        assertTrue(plain.get("excludedLaps").isArray() && plain.get("excludedLaps").isEmpty(), plain.toString());
        assertTrue(plainOverview.get("excludedLaps").isArray() && plainOverview.get("excludedLaps").isEmpty(),
                plainOverview.toString());
    }

    @Test
    void sessionPointsOmitAbsentVehicleDynamicsChannels() throws Exception {
        JsonNode point = getJson("/api/sessions/" + sessionId).get("laps").get(0).get("points").get(0);
        // Existing optional fields keep their explicit nulls ...
        assertTrue(point.has("latitude") && point.get("latitude").isNull());
        assertTrue(point.has("yawRate") && point.get("yawRate").isNull());
        // ... the new ones are only sent when the file has them.
        assertFalse(point.has("lateralAcceleration"));
        assertFalse(point.has("longitudinalAcceleration"));
        assertFalse(point.has("steeringAngle"));
        assertFalse(point.has("engineRpm"));
    }

    @Test
    void sessionPointsCarryVehicleDynamicsChannels() throws Exception {
        String csv = "\"Time\",\"SPEED\",\"THROTTLE\",\"BRAKE\",\"GEAR\",\"G_LAT\",\"G_LON\",\"STEERANGLE\",\"RPMS\"\n"
                + "\"s\",\"km/h\",\"%\",\"%\",\"no\",\"m/s2\",\"m/s2\",\"deg\",\"1/min\"\n"
                + "\"0.000\",\"200\",\"100\",\"0\",\"5\",\"1.52\",\"-0.30\",\"-12\",\"7600\"\n"
                + "\"0.005\",\"200\",\"100\",\"0\",\"5\",\"1.55\",\"-0.31\",\"-13\",\"7610\"\n";
        MockMultipartFile file = new MockMultipartFile("file", "acc_export.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(multipart("/api/telemetry/import").file(file))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode res = json.readTree(body);
        assertTrue(res.get("report").get("warnings").toString().contains("but its values are in g"),
                res.get("report").toString());

        JsonNode point = getJson("/api/sessions/" + res.get("sessionId").asText()).get("laps").get(0).get("points").get(1);
        assertEquals(1.55, point.get("lateralAcceleration").asDouble(), 1e-9);
        assertEquals(-0.31, point.get("longitudinalAcceleration").asDouble(), 1e-9);
        assertEquals(-13, point.get("steeringAngle").asDouble(), 1e-9);
        assertEquals(7610, point.get("engineRpm").asDouble(), 1e-9);
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
