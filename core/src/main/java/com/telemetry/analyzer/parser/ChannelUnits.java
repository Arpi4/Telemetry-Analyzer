package com.telemetry.analyzer.parser;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.TelemetryPoint;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Unit handling of the optional vehicle-dynamics channels, shared by the CSV parsers. Each rule is
 * decided once per file, from the unit label of the column (MoTeC unit row, or a "(unit)" /
 * "[unit]" header suffix) and from its magnitude {@code a99}, the 99th percentile of |value|
 * (nearest rank: the ⌈0.99·n⌉-th smallest of the n non-empty values). A column whose label is
 * present but not recognised is not imported, because its scale cannot be known.
 *
 * <p><b>Accelerations</b> are stored in g:
 * <ul>
 *   <li>label "g" / "G": the values are g and are kept;</li>
 *   <li>label "m/s2" (also "m/s²", "m/s^2", "m/s/s") and {@code a99 < 6}: the values are g despite
 *       the label (Assetto Corsa Competizione writes g under an "m/s2" unit row) and are kept, with
 *       an import warning;</li>
 *   <li>label "m/s2" and {@code a99 >= 6}: genuine m/s², divided by g0 = 9.80665 m/s²;</li>
 *   <li>no label: g when {@code a99 < 6}, otherwise m/s² and divided by g0; the decision is
 *       reported as a warning.</li>
 * </ul>
 * Both acceleration columns come from the same logger, so when both are labelled m/s2 or both
 * are unlabelled the magnitude test uses the larger {@code a99} of the two and gives them the same
 * unit; a gentle longitudinal channel is then not read as g next to a clearly m/s² lateral one.
 * The limit works because a car at track pace spends well over 1 % of a session above 6 m/s²
 * (0.6 g), while no car sustains 6 g; it can misread a genuine m/s² log of a very gentle drive,
 * which is why the decision is always visible in the import report.
 *
 * <p><b>Quantised accelerations.</b> A column whose values lie, apart from rare outliers, on a few
 * whole-number levels (ACC writes G_LON with 1 g resolution) is detected before the unit decision. A quantised
 * longitudinal column is replaced by the longitudinal acceleration derived from the speed
 * channel; a quantised lateral column is kept. Both are reported; the rules are in
 * {@link QuantisedAcceleration}.
 *
 * <p><b>Steering angle</b> is stored in degrees. A column labelled "deg" is kept and one labelled
 * "rad" is converted. Without a label the values are degrees, unless {@code a99 < 2π}: no track
 * session stays within ±6.3° at the steering wheel, so such values are radians (e.g. iRacing
 * "SteeringWheelAngle") and are converted, with an import warning.
 */
final class ChannelUnits {

    /** Standard gravity g0 in m/s². */
    static final double STANDARD_GRAVITY = 9.80665;

    /** {@code a99} below this is read as g, at or above it as m/s². */
    static final double G_MAGNITUDE_LIMIT = 6.0;

    /** Unlabelled steering with {@code a99} below this (one turn in radians) is read as radians. */
    static final double RADIAN_MAGNITUDE_LIMIT = 2 * Math.PI;

    static final double MAGNITUDE_PERCENTILE = 0.99;

    /** Factor that marks a channel as not imported. */
    private static final double DROP = Double.NaN;

    enum AccelerationLabel { G, METRES_PER_SECOND_SQUARED, NONE, UNRECOGNISED }

    enum SteeringLabel { DEGREES, RADIANS, NONE, UNRECOGNISED }

    /**
     * Source column of an optional channel: its header and its unit label ({@code null} when it
     * has none); {@link #ABSENT} when the file has no such column.
     */
    record Column(String name, String unit) {
        static final Column ABSENT = new Column(null, null);

        boolean present() {
            return name != null;
        }
    }

    private ChannelUnits() {
    }

    static AccelerationLabel accelerationLabel(String unit) {
        String u = normalizeUnit(unit);
        if (u.isEmpty()) {
            return AccelerationLabel.NONE;
        }
        return switch (u) {
            case "g", "gs", "g's" -> AccelerationLabel.G;
            case "m/s2", "m/s/s", "ms-2", "m*s-2", "m.s-2", "m·s-2", "mps2", "ms2", "m/sec2", "m/sec/sec" ->
                    AccelerationLabel.METRES_PER_SECOND_SQUARED;
            default -> AccelerationLabel.UNRECOGNISED;
        };
    }

    static SteeringLabel steeringLabel(String unit) {
        String u = normalizeUnit(unit);
        if (u.isEmpty()) {
            return SteeringLabel.NONE;
        }
        return switch (u) {
            case "deg", "degs", "degree", "degrees", "°", "º" -> SteeringLabel.DEGREES;
            case "rad", "rads", "radian", "radians" -> SteeringLabel.RADIANS;
            default -> SteeringLabel.UNRECOGNISED;
        };
    }

    /** Nearest-rank percentile of |value| over the non-null values; NaN when there are none. */
    static double percentileOfMagnitude(List<TelemetryPoint> points, Function<TelemetryPoint, Double> channel,
                                        double percentile) {
        double[] values = new double[points.size()];
        int n = 0;
        for (TelemetryPoint p : points) {
            Double v = channel.apply(p);
            if (v != null && Double.isFinite(v)) {
                values[n++] = Math.abs(v);
            }
        }
        if (n == 0) {
            return Double.NaN;
        }
        Arrays.sort(values, 0, n);
        int rank = (int) Math.ceil(percentile * n);
        return values[Math.clamp(rank - 1, 0, n - 1)];
    }

    /**
     * Decides the unit of the acceleration and steering columns (rules in the class comment) and
     * rewrites {@code points} in place so that they hold g and degrees; a column with an
     * unrecognised unit is cleared. A quantised longitudinal column is replaced by the
     * acceleration derived from speed, and a quantised lateral column is kept, both with a warning
     * (rules in {@link QuantisedAcceleration}). Column names are only used in the warnings.
     *
     * @param points      the samples of the whole file in time order, before the lap split
     * @param speedColumn header of the speed column, named in the warning about derived values
     */
    static void normalizeChannels(List<TelemetryPoint> points, Column lateral, Column longitudinal, Column steering,
                                  String speedColumn, String format, ImportReport report) {
        double lateralA99 = magnitude(points, lateral, TelemetryPoint::lateralAcceleration);
        double longitudinalA99 = magnitude(points, longitudinal, TelemetryPoint::longitudinalAcceleration);
        QuantisedAcceleration.Levels lateralLevels = lateral.present()
                ? QuantisedAcceleration.quantisedLevels(points, TelemetryPoint::lateralAcceleration) : null;
        QuantisedAcceleration.Levels longitudinalLevels = longitudinal.present()
                ? QuantisedAcceleration.quantisedLevels(points, TelemetryPoint::longitudinalAcceleration) : null;

        double lateralFactor = toGFactor(lateral, lateralA99, longitudinal, longitudinalA99, format, report);
        if (lateralLevels != null && !Double.isNaN(lateralFactor)) {
            report.addWarning(quantisedSubject(format, lateral, lateralLevels, lateralFactor)
                    + "; kept as is, because lateral acceleration cannot be derived from speed alone.");
        }

        double[] derivedLongitudinal = null;
        double longitudinalFactor;
        if (longitudinalLevels != null) {
            // The column is replaced, so its own unit decision only describes the resolution and
            // is not reported: "imported as g" would contradict the replacement.
            double rawFactor = toGFactor(longitudinal, longitudinalA99, lateral, lateralA99, format,
                    new ImportReport(""));
            derivedLongitudinal = QuantisedAcceleration.longitudinalFromSpeed(points);
            report.addWarning(String.format(Locale.ROOT, "%s; replaced by longitudinal acceleration derived from "
                            + "\"%s\" (dv/dt as a central difference over t +/- %.2f s, divided by %s m/s2).",
                    quantisedSubject(format, longitudinal, longitudinalLevels, rawFactor),
                    speedColumn == null ? "speed" : speedColumn, QuantisedAcceleration.HALF_WINDOW_S,
                    STANDARD_GRAVITY));
            longitudinalFactor = 1.0;
        } else {
            longitudinalFactor = toGFactor(longitudinal, longitudinalA99, lateral, lateralA99, format, report);
        }

        double steeringFactor = toDegreesFactor(points, steering, format, report);
        if (lateralFactor == 1.0 && longitudinalFactor == 1.0 && steeringFactor == 1.0 && derivedLongitudinal == null) {
            return;
        }
        DoublePool pool = new DoublePool();
        for (int i = 0; i < points.size(); i++) {
            TelemetryPoint p = points.get(i);
            Double longitudinalValue = derivedLongitudinal != null
                    ? pool.share(derivedLongitudinal[i])
                    : scale(p.longitudinalAcceleration(), longitudinalFactor, pool);
            points.set(i, p.withAccelerationsAndSteering(
                    scale(p.lateralAcceleration(), lateralFactor, pool),
                    longitudinalValue,
                    scale(p.steeringAngle(), steeringFactor, pool)));
        }
    }

    /**
     * Start of the warning about a quantised column: its name, its levels and its resolution in the
     * unit the column was read in ({@code factor}: 1 for g, 1/g0 for m/s², NaN for an unrecognised
     * unit).
     */
    private static String quantisedSubject(String format, Column column, QuantisedAcceleration.Levels levels,
                                           double factor) {
        StringBuilder values = new StringBuilder();
        for (double level : levels.values()) {
            values.append(values.isEmpty() ? "" : ", ").append(String.format(Locale.ROOT, "%.0f", level));
        }
        String resolution;
        if (factor == 1.0) {
            resolution = "1 g";
        } else if (Double.isNaN(factor)) {
            resolution = "1 " + column.unit().trim();
        } else {
            resolution = String.format(Locale.ROOT, "1 m/s2 = %.3f g", factor);
        }
        int n = levels.values().length;
        int others = levels.otherValues();
        // rare outliers off the levels (collision spikes) are counted, not listed
        String outliers = others == 0 ? ""
                : others == 1 ? ", and 1 sample with another value"
                : String.format(Locale.ROOT, ", and %d samples with other values", others);
        return String.format(Locale.ROOT, "%s: column \"%s\" holds whole numbers %s(%d distinct value%s: %s%s), "
                        + "a resolution of %s", format, column.name(), others == 0 ? "only " : "", n, n == 1 ? "" : "s",
                values, outliers, resolution);
    }

    private static double magnitude(List<TelemetryPoint> points, Column column,
                                    Function<TelemetryPoint, Double> channel) {
        return column.present() ? percentileOfMagnitude(points, channel, MAGNITUDE_PERCENTILE) : Double.NaN;
    }

    private static double toGFactor(Column column, double a99, Column partner, double partnerA99,
                                    String format, ImportReport report) {
        if (!column.present() || Double.isNaN(a99)) {
            return 1.0;
        }
        AccelerationLabel label = accelerationLabel(column.unit());
        String subject = format + ": column \"" + column.name() + "\"";
        switch (label) {
            case G -> {
                return 1.0;
            }
            case UNRECOGNISED -> {
                report.addWarning(subject + " has an unrecognised acceleration unit \"" + column.unit().trim()
                        + "\"; channel not imported.");
                return DROP;
            }
            default -> {
                // METRES_PER_SECOND_SQUARED or NONE: decided by magnitude, below.
            }
        }
        String limit = String.format(Locale.ROOT, "%.0f", G_MAGNITUDE_LIMIT);
        String magnitude = String.format(Locale.ROOT, "99th percentile of |value| = %.2f", a99);
        boolean partnerDecides = partner.present() && accelerationLabel(partner.unit()) == label
                && a99 < G_MAGNITUDE_LIMIT && partnerA99 >= G_MAGNITUDE_LIMIT;
        if (partnerDecides) {
            String reason = magnitude + String.format(Locale.ROOT,
                    "; decided together with column \"%s\" of the same file, 99th percentile %.2f",
                    partner.name(), partnerA99);
            report.addWarning(label == AccelerationLabel.METRES_PER_SECOND_SQUARED
                    ? subject + " converted from m/s2 to g (" + reason + ")."
                    : subject + " has no acceleration unit; read as m/s2 and converted to g (" + reason + ").");
            return 1.0 / STANDARD_GRAVITY;
        }
        boolean magnitudeIsG = a99 < G_MAGNITUDE_LIMIT;
        if (label == AccelerationLabel.METRES_PER_SECOND_SQUARED) {
            if (magnitudeIsG) {
                report.addWarning(subject + " is labelled " + column.unit().trim() + " but its values are in g ("
                        + magnitude + ", below " + limit + "); imported as g.");
                return 1.0;
            }
            report.addWarning(subject + " converted from m/s2 to g (" + magnitude + ").");
            return 1.0 / STANDARD_GRAVITY;
        }
        if (magnitudeIsG) {
            report.addWarning(subject + " has no acceleration unit; read as g (" + magnitude + ", below " + limit
                    + ").");
            return 1.0;
        }
        report.addWarning(subject + " has no acceleration unit; read as m/s2 and converted to g (" + magnitude
                + ", not below " + limit + ").");
        return 1.0 / STANDARD_GRAVITY;
    }

    private static double toDegreesFactor(List<TelemetryPoint> points, Column steering, String format,
                                          ImportReport report) {
        if (!steering.present()) {
            return 1.0;
        }
        String subject = format + ": column \"" + steering.name() + "\"";
        switch (steeringLabel(steering.unit())) {
            case DEGREES -> {
                return 1.0;
            }
            case RADIANS -> {
                return 180.0 / Math.PI;
            }
            case UNRECOGNISED -> {
                report.addWarning(subject + " has an unrecognised steering unit \"" + steering.unit().trim()
                        + "\"; channel not imported.");
                return DROP;
            }
            default -> {
                double a99 = percentileOfMagnitude(points, TelemetryPoint::steeringAngle, MAGNITUDE_PERCENTILE);
                if (Double.isNaN(a99) || a99 >= RADIAN_MAGNITUDE_LIMIT) {
                    return 1.0;
                }
                report.addWarning(String.format(Locale.ROOT, "%s has no unit and its values are small for degrees "
                        + "(99th percentile of |value| = %.2f, below %.2f); read as radians and converted to degrees.",
                        subject, a99, RADIAN_MAGNITUDE_LIMIT));
                return 180.0 / Math.PI;
            }
        }
    }

    /** {@code value} times {@code factor}; {@code null} when the value is missing or the channel is dropped. */
    private static Double scale(Double value, double factor, DoublePool pool) {
        if (value == null || Double.isNaN(factor)) {
            return null;
        }
        if (factor == 1.0) {
            return value;
        }
        return pool.share(value * factor);
    }

    private static String normalizeUnit(String unit) {
        if (unit == null) {
            return "";
        }
        String u = unit.replace("\"", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                .replace('²', '2').replace("^", "");
        return "..".equals(u) ? "" : u;
    }
}
