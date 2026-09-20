package com.telemetry.analyzer;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.service.AnalyzerServices;
import com.telemetry.analyzer.service.TelemetryImportService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryImportServiceTest {

    @Test
    void storesCsvSessionLocally() {
        String csv = "timestamp,speed,throttle,brake,gear\n0.0,10,20,0,1\n0.1,20,40,0,2\n";
        AnalyzerServices services = new AnalyzerServices();
        TelemetryImportService.ImportResult result =
                services.importService().importFile(csv.getBytes(StandardCharsets.UTF_8), "unit.csv");

        assertNotNull(result.session());
        assertTrue(result.report().getErrors().isEmpty());
        assertEquals(1, services.sessionStore().list().size());
        ImportReport report = result.report();
        assertEquals("unit.csv", report.getSourceFile());
    }
}
