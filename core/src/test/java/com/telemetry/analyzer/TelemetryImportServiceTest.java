package com.telemetry.analyzer;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.service.AnalyzerServices;
import com.telemetry.analyzer.service.TelemetryImportService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void rejectsBinaryMotecLdFileWithClearError() {
        byte[] binary = new byte[2048];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) (i * 37);
        }
        AnalyzerServices services = new AnalyzerServices();
        TelemetryImportService.ImportResult result = services.importService().importFile(binary, "session.ld");

        assertNull(result.session());
        assertTrue(result.report().getErrors().stream().anyMatch(e -> e.contains("binary .ld")),
                result.report().getErrors().toString());
        assertTrue(services.sessionStore().list().isEmpty());
    }

    @Test
    void skipsMalformedRowsAndReportsThem() {
        String csv = "timestamp,speed,throttle,brake,gear\n0.0,10,20,0,1\n0.1,abc,40,0,2\n0.2,30,60,0,3\n";
        AnalyzerServices services = new AnalyzerServices();
        TelemetryImportService.ImportResult result =
                services.importService().importFile(csv.getBytes(StandardCharsets.UTF_8), "rows.csv");

        assertNotNull(result.session());
        assertEquals(2, result.session().laps().get(0).points().size());
        // header-only CSVs with these channel names go through the channel-row profile
        assertTrue(result.report().getWarnings().stream().anyMatch(w -> w.toLowerCase().contains("skipped 1")),
                result.report().getWarnings().toString());
        assertTrue(result.report().getErrors().isEmpty());
    }

    @Test
    void reportsUnsupportedFileTypeWithoutThrowing() {
        AnalyzerServices services = new AnalyzerServices();
        TelemetryImportService.ImportResult result =
                services.importService().importFile("x".getBytes(StandardCharsets.UTF_8), "notes.xlsx");

        assertNull(result.session());
        assertEquals(1, result.report().getErrors().size());
        assertTrue(result.report().getErrors().get(0).startsWith("Unsupported file type"));
    }
}
