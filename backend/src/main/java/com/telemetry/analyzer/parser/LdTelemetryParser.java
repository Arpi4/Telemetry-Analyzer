package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class LdTelemetryParser implements TelemetryParser {

    private final GenericDelimitedParser genericDelimitedParser;

    public LdTelemetryParser(GenericDelimitedParser genericDelimitedParser) {
        this.genericDelimitedParser = genericDelimitedParser;
    }

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ld") || lower.endsWith(".ldx");
    }

    @Override
    public SessionData parse(byte[] content, String sourceFile, ImportReport report) {
        String lower = sourceFile.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ldx")) {
            SessionData parsed = parseLdxXml(content, sourceFile, report);
            if (parsed != null) {
                return parsed;
            }
            report.addWarning("LDX XML parse failed, falling back to delimited parser.");
        } else if (looksBinary(content)) {
            // MoTeC LD is typically proprietary binary; parsing requires vendor-specific spec.
            report.addError("MoTeC binary .ld file detected. Export CSV or ASCII from MoTeC i2, or use an XML-based .ldx export for this tool.");
            return new SessionData("session-empty", sourceFile, Instant.now(), List.of());
        }

        report.addWarning("Using fallback text extraction for LD/LDX.");
        String text = new String(content, StandardCharsets.UTF_8);
        return genericDelimitedParser.parseTextTelemetry(text, sourceFile, report);
    }

    private SessionData parseLdxXml(byte[] content, String sourceFile, ImportReport report) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
            document.getDocumentElement().normalize();

            List<TelemetryPoint> points = new ArrayList<>();
            NodeList rows = document.getElementsByTagName("row");
            if (rows.getLength() == 0) {
                rows = document.getElementsByTagName("sample");
            }
            if (rows.getLength() == 0) {
                rows = document.getElementsByTagName("point");
            }

            for (int i = 0; i < rows.getLength(); i++) {
                Node node = rows.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element el = (Element) node;
                double timestamp = readNumber(el, "timestamp", "time", "t", "0");
                double speed = readNumber(el, "speed", "velocity", "kmh", null);
                double throttle = readNumber(el, "throttle", "gas", "accel", null);
                double brake = readNumber(el, "brake", "brk", "brakepedal", null);
                int gear = (int) readNumber(el, "gear", "g", "gearpos", null);
                Double lat = readOptionalNumber(el, "lat", "latitude", "gps_lat");
                Double lon = readOptionalNumber(el, "lon", "longitude", "gps_lon");
                Double dist = readOptionalNumber(el, "distance", "dist", "odo");

                if (Double.isNaN(speed) || Double.isNaN(throttle) || Double.isNaN(brake) || Double.isNaN(gear)) {
                    continue;
                }
                points.add(new TelemetryPoint(timestamp, speed, throttle, brake, gear, lat, lon, dist));
            }

            if (points.isEmpty()) {
                report.addError("No telemetry samples found in LDX XML.");
                return null;
            }
            return new SessionData(
                    "session-" + Math.abs(sourceFile.hashCode()) + "-" + System.currentTimeMillis(),
                    sourceFile,
                    Instant.now(),
                    List.of(new LapData("lap-1", points))
            );
        } catch (Exception ex) {
            report.addWarning("LDX parse exception: " + ex.getMessage());
            return null;
        }
    }

    private static boolean looksBinary(byte[] content) {
        int control = 0;
        int limit = Math.min(content.length, 512);
        for (int i = 0; i < limit; i++) {
            int value = Byte.toUnsignedInt(content[i]);
            if ((value < 9 || value > 126) && value != 10 && value != 13) {
                control++;
            }
        }
        return limit > 0 && ((double) control / (double) limit) > 0.2;
    }

    private static double readNumber(Element element, String keyA, String keyB, String keyC, String fallback) {
        String text = readValue(element, keyA, keyB, keyC, fallback);
        if (text == null || text.isBlank()) {
            return Double.NaN;
        }
        return Double.parseDouble(text.trim());
    }

    private static Double readOptionalNumber(Element element, String keyA, String keyB, String keyC) {
        String text = readValue(element, keyA, keyB, keyC, null);
        if (text == null || text.isBlank()) {
            return null;
        }
        return Double.parseDouble(text.trim());
    }

    private static String readValue(Element element, String keyA, String keyB, String keyC, String fallback) {
        String[] keys = {keyA, keyB, keyC};
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            if (element.hasAttribute(key)) {
                return element.getAttribute(key);
            }
            NodeList byTag = element.getElementsByTagName(key);
            if (byTag.getLength() > 0) {
                String value = byTag.item(0).getTextContent();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return fallback;
    }
}
