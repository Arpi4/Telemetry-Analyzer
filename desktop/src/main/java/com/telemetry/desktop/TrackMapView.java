package com.telemetry.desktop;

import com.telemetry.analyzer.analysis.TrackReconstructor.Source;
import com.telemetry.analyzer.analysis.TrackReconstructor.TrackOutline;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Track map drawn on a canvas: the driven line coloured by a per-sample value (speed, or time
 * gained/lost against a reference lap), numbered markers and a cursor linked to the charts.
 */
final class TrackMapView extends Region {

    enum Scale { SPEED, DELTA }

    record Marker(double distance, String label, Color color) {
    }

    private static final Color NEUTRAL = Color.web("#64748b");
    private static final Color GAIN = Color.web("#22c55e");
    private static final Color LOSS = Color.web("#ef4444");
    private static final Color CURSOR = Color.web("#facc15");
    /** Same as the chart plot background; label halos use it so text stays readable over the line. */
    private static final Color BACKGROUND = Color.web("#0a0e14");
    private static final Color TRACK_BED = Color.web("#1a2230");
    private static final Color TEXT = Color.web("#e8edf4");
    private static final Color MUTED = Color.web("#8b9cb3");
    /** Below this height the caption is hidden. */
    private static final double SMALL_HEIGHT = 200;
    private static final String NO_LAP = "Select a lap to draw its track map.";

    private final Canvas canvas = new Canvas();
    private final Label placeholder = new Label(NO_LAP);
    private final Label caption = new Label();

    private TrackOutline outline;
    private double[] values = new double[0];
    private Scale scale = Scale.SPEED;
    private List<Marker> markers = List.of();
    private double cursorDistance = Double.NaN;
    private String lowLabel = "slow";
    private String highLabel = "fast";

    TrackMapView() {
        getStyleClass().add("track-map");
        placeholder.getStyleClass().add("muted");
        placeholder.setWrapText(true);
        placeholder.setTextAlignment(TextAlignment.CENTER);
        caption.getStyleClass().add("map-caption");
        getChildren().addAll(canvas, placeholder, caption);
        setMinSize(200, 120);
        setPrefSize(420, 380);
    }

    void setTrack(TrackOutline outline) {
        this.outline = outline;
        this.markers = List.of();
        this.cursorDistance = Double.NaN;
        placeholder.setText(outline == null ? NO_LAP : "No GPS or yaw-rate channel in this lap: track map unavailable.");
        caption.setText(outline == null || outline.isEmpty() ? "" : outline.source() == Source.GPS
                ? "GPS projection"
                : String.format(Locale.ROOT, "From yaw rate · closure %.1f° / %.0f m corrected",
                outline.headingClosureDeg(), outline.positionClosureM()));
        redraw();
    }

    /** Values aligned with the outline samples. */
    void setValues(double[] values, Scale scale, String lowLabel, String highLabel) {
        this.values = values == null ? new double[0] : values;
        this.scale = scale;
        this.lowLabel = lowLabel;
        this.highLabel = highLabel;
        redraw();
    }

    void setMarkers(List<Marker> markers) {
        this.markers = markers;
        redraw();
    }

    void setCursorDistance(double distance) {
        this.cursorDistance = distance;
        redraw();
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        canvas.setWidth(w);
        canvas.setHeight(h);
        placeholder.resizeRelocate(24, h / 2 - 30, Math.max(0, w - 48), 60);
        caption.resizeRelocate(10, 6, Math.max(0, w - 20), caption.prefHeight(-1));
        caption.setVisible(h >= SMALL_HEIGHT); // a small map keeps its height for the track
        redraw();
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);
        boolean has = outline != null && !outline.isEmpty() && outline.x().length > 1;
        placeholder.setVisible(!has);
        if (!has || w < 10 || h < 10) {
            return;
        }
        // text sizes follow the UI font, so the map scales with it (screenshot mode uses a larger one)
        double fs = placeholder.getFont().getSize();
        String family = placeholder.getFont().getFamily();
        double[] x = outline.x();
        double[] y = outline.y();
        int n = x.length;
        double minX = Arrays.stream(x).min().orElse(0);
        double maxX = Arrays.stream(x).max().orElse(1);
        double minY = Arrays.stream(y).min().orElse(0);
        double maxY = Arrays.stream(y).max().orElse(1);
        double pad = 22;
        double top = h >= SMALL_HEIGHT ? fs * 2.2 : 4;
        double bottom = fs * 3.4;
        double boxW = Math.max(1, w - 2 * pad);
        double boxH = Math.max(1, h - top - bottom - pad);
        double sc = Math.min(boxW / Math.max(1e-9, maxX - minX), boxH / Math.max(1e-9, maxY - minY));
        double ox = pad + (boxW - sc * (maxX - minX)) / 2;
        double oy = top + pad / 2 + (boxH - sc * (maxY - minY)) / 2;

        double lo = 0;
        double hi = 1;
        boolean colored = values.length == n;
        if (colored) {
            if (scale == Scale.SPEED) {
                lo = Arrays.stream(values).min().orElse(0);
                hi = Arrays.stream(values).max().orElse(1);
            } else {
                hi = percentileAbs(values, 0.95);
            }
        }

        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        g.setLineWidth(10);
        g.setStroke(TRACK_BED);
        strokePath(g, x, y, minX, maxY, sc, ox, oy, 0, n);

        int step = Math.max(1, n / 4000);
        g.setLineWidth(3.5);
        for (int i = step; i < n; i += step) {
            g.setStroke(colored ? color(values[i], lo, hi) : Color.web("#ff3d2e"));
            g.strokeLine(px(x[i - step], minX, sc, ox), py(y[i - step], maxY, sc, oy),
                    px(x[i], minX, sc, ox), py(y[i], maxY, sc, oy));
        }

        drawStartFinish(g, x, y, minX, maxY, sc, ox, oy);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        g.setFont(Font.font(family, FontWeight.BOLD, fs * 0.85));
        haloText(g, "S/F", px(x[0], minX, sc, ox) + 10, py(y[0], maxY, sc, oy) - 11, TEXT);

        g.setFont(Font.font(family, FontWeight.BOLD, fs * 0.78));
        for (Marker m : markers) {
            int i = indexOfDistance(m.distance());
            double mx = px(x[i], minX, sc, ox);
            double my = py(y[i], maxY, sc, oy);
            g.setFill(BACKGROUND);
            g.fillOval(mx - 5.5, my - 5.5, 11, 11);
            g.setFill(m.color());
            g.fillOval(mx - 4, my - 4, 8, 8);
            haloText(g, m.label(), mx + 7, my - 8, TEXT);
        }

        if (!Double.isNaN(cursorDistance)) {
            int i = indexOfDistance(cursorDistance);
            double cx = px(x[i], minX, sc, ox);
            double cy = py(y[i], maxY, sc, oy);
            g.setFill(CURSOR.deriveColor(0, 1, 1, 0.28));
            g.fillOval(cx - 11, cy - 11, 22, 22);
            g.setFill(CURSOR);
            g.fillOval(cx - 6, cy - 6, 12, 12);
            g.setStroke(BACKGROUND);
            g.setLineWidth(2);
            g.strokeOval(cx - 6, cy - 6, 12, 12);
        }

        if (colored) {
            drawLegend(g, w, h, lo, hi, fs, family);
        }
    }

    /** Chequered line across the track at the first sample. */
    private void drawStartFinish(GraphicsContext g, double[] x, double[] y, double minX, double maxY,
                                 double sc, double ox, double oy) {
        double sx = px(x[0], minX, sc, ox);
        double sy = py(y[0], maxY, sc, oy);
        int k = Math.max(1, indexOfDistance(outline.distance()[0] + 15));
        double angle = Math.atan2(py(y[k], maxY, sc, oy) - sy, px(x[k], minX, sc, ox) - sx);
        double c = 3.5;
        g.save();
        g.translate(sx, sy);
        g.rotate(Math.toDegrees(angle));
        g.setFill(BACKGROUND);
        g.fillRect(-c - 1, -2 * c - 1, 2 * c + 2, 4 * c + 2);
        for (int i = 0; i < 2; i++) {
            for (int j = -2; j < 2; j++) {
                g.setFill(Math.floorMod(i + j, 2) == 0 ? Color.WHITE : Color.web("#111827"));
                g.fillRect((i - 1) * c, j * c, c, c);
            }
        }
        g.restore();
    }

    private void drawLegend(GraphicsContext g, double w, double h, double lo, double hi, double fs, String family) {
        double barW = Math.min(180, w * 0.45);
        double barH = 6;
        double bx = w - barW - 14;
        double by = h - fs * 2.1;
        for (int k = 0; k < barW; k++) {
            double f = k / barW;
            double v = scale == Scale.SPEED ? lo + f * (hi - lo) : (-1 + 2 * f) * hi;
            g.setStroke(color(v, lo, hi));
            g.setLineWidth(1);
            g.strokeLine(bx + k + 0.5, by, bx + k + 0.5, by + barH);
        }
        String title = scale == Scale.SPEED
                ? String.format(Locale.ROOT, "Speed  %.0f–%.0f km/h", lo, hi)
                : String.format(Locale.ROOT, "Time loss rate  ±%.2f s / 100 m", hi);
        g.setFill(MUTED);
        g.setFont(Font.font(family, fs * 0.78));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.BOTTOM);
        g.fillText(title, bx, by - 3);
        g.setTextBaseline(VPos.TOP);
        g.fillText(lowLabel, bx, by + barH + 3);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(highLabel, bx + barW, by + barH + 3);
    }

    /** Text with a background-coloured outline, readable on top of the coloured line. */
    private static void haloText(GraphicsContext g, String text, double x, double y, Color color) {
        g.setStroke(BACKGROUND);
        g.setLineWidth(3.5);
        g.strokeText(text, x, y);
        g.setFill(color);
        g.fillText(text, x, y);
    }

    private Color color(double v, double lo, double hi) {
        if (scale == Scale.SPEED) {
            double f = hi > lo ? Math.clamp((v - lo) / (hi - lo), 0, 1) : 0.5;
            return Color.hsb(240 * (1 - f), 0.85, 0.95);
        }
        double f = hi > 0 ? Math.clamp(v / hi, -1, 1) : 0;
        return f < 0 ? NEUTRAL.interpolate(GAIN, -f) : NEUTRAL.interpolate(LOSS, f);
    }

    private int indexOfDistance(double distance) {
        int i = ChartUtil.indexAtOrAfter(outline.distance(), distance);
        return Math.clamp(i, 0, outline.x().length - 1);
    }

    private static void strokePath(GraphicsContext g, double[] x, double[] y, double minX, double maxY,
                                   double sc, double ox, double oy, int from, int to) {
        g.beginPath();
        g.moveTo(px(x[from], minX, sc, ox), py(y[from], maxY, sc, oy));
        int step = Math.max(1, (to - from) / 4000);
        for (int i = from + step; i < to; i += step) {
            g.lineTo(px(x[i], minX, sc, ox), py(y[i], maxY, sc, oy));
        }
        g.stroke();
    }

    private static double px(double x, double minX, double sc, double ox) {
        return ox + (x - minX) * sc;
    }

    private static double py(double y, double maxY, double sc, double oy) {
        return oy + (maxY - y) * sc;
    }

    private static double percentileAbs(double[] v, double q) {
        double[] a = Arrays.stream(v).map(Math::abs).sorted().toArray();
        if (a.length == 0) {
            return 1;
        }
        double p = a[Math.min(a.length - 1, (int) (q * a.length))];
        return p > 0 ? p : 1;
    }
}
