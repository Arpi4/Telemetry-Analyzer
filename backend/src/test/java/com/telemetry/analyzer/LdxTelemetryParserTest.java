package com.telemetry.analyzer;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.parser.GenericDelimitedParser;
import com.telemetry.analyzer.parser.LdTelemetryParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LdxTelemetryParserTest {

    @Test
    void parsesSimpleLdxXmlRows() {
        String xml = """
                <telemetry>
                  <row timestamp="0.0" speed="10" throttle="20" brake="0" gear="1" lat="46.25" lon="20.14"/>
                  <row timestamp="0.1" speed="20" throttle="30" brake="0" gear="2" lat="46.26" lon="20.15"/>
                </telemetry>
                """;

        LdTelemetryParser parser = new LdTelemetryParser(new GenericDelimitedParser());
        ImportReport report = new ImportReport("sample.ldx");
        SessionData session = parser.parse(xml.getBytes(StandardCharsets.UTF_8), "sample.ldx", report);

        assertNotNull(session);
        assertFalse(session.laps().isEmpty());
        assertEquals(2, session.laps().get(0).points().size());
        assertFalse(report.getErrors().size() > 0);
    }
}
