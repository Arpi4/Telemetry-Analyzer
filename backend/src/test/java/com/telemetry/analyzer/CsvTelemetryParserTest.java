package com.telemetry.analyzer;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.parser.CsvTelemetryParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CsvTelemetryParserTest {

    @Test
    void parsesRequiredChannels() {
        String csv = "timestamp,speed,throttle,brake,gear\n" +
                "0.0,0,0,0,1\n" +
                "0.1,10,20,0,1\n";

        CsvTelemetryParser parser = new CsvTelemetryParser();
        ImportReport report = new ImportReport("test.csv");
        SessionData session = parser.parse(csv.getBytes(StandardCharsets.UTF_8), "test.csv", report);

        assertNotNull(session);
        assertEquals(1, session.laps().size());
        assertEquals(2, session.laps().get(0).points().size());
        assertTrue(report.getErrors().isEmpty());
    }

    @Test
    void parsesMotecStyleSemicolonHeaders() {
        String csv = "Time;Speed (km/h);Throttle Pos;Brake Pos;Gear\n" +
                "0,0;0;0;0;1\n" +
                "0,1;10;20;0;1\n";

        CsvTelemetryParser parser = new CsvTelemetryParser();
        ImportReport report = new ImportReport("export.csv");
        SessionData session = parser.parse(csv.getBytes(StandardCharsets.UTF_8), "motec_export.csv", report);

        assertNotNull(session);
        assertEquals(1, session.laps().size());
        assertEquals(2, session.laps().get(0).points().size());
        assertTrue(report.getErrors().isEmpty());
    }
}
