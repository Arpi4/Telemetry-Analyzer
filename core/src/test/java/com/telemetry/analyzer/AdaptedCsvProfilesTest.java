package com.telemetry.analyzer;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.parser.AdaptedCsvProfiles;
import com.telemetry.analyzer.parser.CsvTelemetryParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptedCsvProfilesTest {

    @Test
    void motecWorkbookParsesDataRows() {
        String csv = """
                "Format","MoTeC CSV File"
                "Venue","monza"


                "Time","Distance","SPEED","THROTTLE","BRAKE","GEAR"
                "s","m","km/h","%","%","no"
                "0.000","0","232.0","100","0","5"
                "0.005","0","232.0","100","0","5"
                """;

        ImportReport report = new ImportReport("m.csv");
        SessionData session = AdaptedCsvProfiles.parseMoTeCWorkbook(csv, "m.csv", report);

        assertFalse(session.laps().isEmpty());
        assertEquals(2, session.laps().get(0).points().size());
        assertTrue(report.getErrors().isEmpty());
    }

    @Test
    void motecBeaconMarkersSplitLapsAndRebaseDistance() {
        String csv = """
                "Format","MoTeC CSV File"
                "Beacon Markers","1.0 2.0 "


                "Time","Distance","SPEED","THROTTLE","BRAKE","GEAR"
                "s","m","km/h","%","%","no"
                "0.0","0","100","100","0","4"
                "0.5","14","100","100","0","4"
                "1.0","28","100","100","0","4"
                "1.5","42","100","0","80","3"
                "2.0","56","90","50","0","3"
                "2.5","70","90","100","0","N"
                """;

        ImportReport report = new ImportReport("m.csv");
        SessionData session = AdaptedCsvProfiles.parseMoTeCWorkbook(csv, "m.csv", report);

        assertEquals(3, session.laps().size());
        // Lap 2 = samples at 1.0 and 1.5 s, closed by the first sample of lap 3 (2.0 s).
        assertEquals(3, session.laps().get(1).points().size());
        // Lap 2 starts at 28 m of the outing; per-lap distance must start from zero.
        assertEquals(0.0, session.laps().get(1).points().get(0).distanceMeters(), 1e-9);
        assertEquals(14.0, session.laps().get(1).points().get(1).distanceMeters(), 1e-9);
        assertEquals(28.0, session.laps().get(1).points().get(2).distanceMeters(), 1e-9);
        // Raw timestamps stay absolute; lap time = last - first = beacon interval.
        assertEquals(1.0, session.laps().get(1).points().get(0).timestamp(), 1e-9);
        assertEquals(1.0, session.laps().get(1).points().getLast().timestamp()
                - session.laps().get(1).points().getFirst().timestamp(), 1e-9);
        // Non-numeric gear label is kept as neutral instead of dropping the row.
        assertEquals(0, session.laps().get(2).points().get(1).gear());
    }

    @Test
    void openF1MalformedQuoteRepairedAndParsedViaCsvParser() {
        String header = "date_start,driver_number,lap_duration,lap_number,i1_speed,i2_speed,st_speed,is_pit_out_lap\n";
        String badRow = "\"2025-12-07 13:03:27.584000+00:00,44,99.744,1,279,313,315,False\n";

        CsvTelemetryParser parser = new CsvTelemetryParser();
        ImportReport report = new ImportReport("laps.csv");
        SessionData session = parser.parse((header + badRow).getBytes(StandardCharsets.UTF_8), "laps.csv", report);

        assertTrue(report.getErrors().isEmpty());
        assertEquals(1, session.laps().get(0).points().size());
        assertEquals(1.0, session.laps().get(0).points().get(0).timestamp(), 0.01);
    }

    @Test
    void openF1RowWithEscapedQuotesInSegmentsParses() {
        String header = "date_start,driver_number,lap_duration,lap_number,i1_speed,i2_speed,st_speed,is_pit_out_lap,segments_sector_1\n";
        String badRow = "\"2025-12-07 13:03:27.584000+00:00,44,99.744,1,279,313,315,False,\"\"[2049, 2049]\"\"\n";

        ImportReport report = new ImportReport("laps.csv");
        SessionData session = AdaptedCsvProfiles.parseOpenF1Laps(header + badRow, "laps.csv", report);

        assertTrue(report.getErrors().isEmpty(), report.getErrors().toString());
        assertEquals(1, session.laps().get(0).points().size());
    }
}
