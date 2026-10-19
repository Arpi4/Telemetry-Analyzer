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
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.Arrays;
import java.util.List;

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

    private final Canvas canvas = new Canvas();
    private final Label placeholder = new Label("No GPS or yaw-rate channel in this lap: track map unavailable.");
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
        caption.getStyleClass().add("muted");
        getChildren().addAll(canvas, placeholder, caption);
        setMinSize(220, 220);
        setPrefSize(420, 380);
    }

    void setTrack(TrackOutline outline) {
        this.outline = outline;
        this.markers = List.of();
        this.cursorDistance = Double.NaN;
        caption.setText(outline == null || outline.isEmpty() ? "" : outline.source() == Source.GPS
                ? "GPS projection"
                : String.format("Reconstructed from yaw rate · closure %.1f° / %.0f m corrected",
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
        placeholder.resizeRelocate(16, 16, Math.max(0, w - 32), 60);
        caption.resizeRelocate(10, h - 22, Math.max(0, w - 20), 18);
        redraw();
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.setFill(Color.web("#0d1118"));
        g.fillRect(0, 0, w, h);
        boolean has = outline != null && !outline.isEmpty() && outline.x().length > 1;
        placeholder.setVisible(!has);
        if (!has || w < 10 || h < 10) {
            return;
        }
        double[] x = outline.x();
        double[] y = outline.y();
        int n = x.length;
        double minX = Arrays.stream(x).min().orElse(0);
        double maxX = Arrays.stream(x).max().orElse(1);
        double minY = Arrays.stream(y).min().orElse(0);
        double maxY = Arrays.stream(y).max().orElse(1);
        double pad = 28;
        double bottomReserve = 46;
        double sc = Math.min((w - 2 * pad) / Math.max(1e-9, maxX - minX), (h - 2 * pad - bottomReserve) / Math.max(1e-9, maxY - minY));
        double ox = pad + ((w - 2 * pad) - sc * (maxX - minX)) / 2;
        double oy = pad + ((h - 2 * pad - bottomReserve) - sc * (maxY - minY)) / 2;

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
        g.setLineWidth(6);
        g.setStroke(Color.web("#1f2937"));
        strokePath(g, x, y, minX, maxY, sc, ox, oy, 0, n);

        int step = Math.max(1, n / 4000);
        g.setLineWidth(3);
        for (int i = step; i < n; i += step) {
            g.setStroke(colored ? color(values[i], lo, hi) : Color.web("#ff3d2e"));
            g.strokeLine(px(x[i - step], minX, sc, ox), py(y[i - step], maxY, sc, oy),
                    px(x[i], minX, sc, ox), py(y[i], maxY, sc, oy));
        }

        // start / finish
        g.setFill(Color.WHITE);
        g.fillOval(px(x[0], minX, sc, ox) - 5, py(y[0], maxY, sc, oy) - 5, 10, 10);
        g.setFont(Font.font(11));
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        g.fillText("S/F", px(x[0], minX, sc, ox) + 8, py(y[0], maxY, sc, oy) - 8);

        g.setFont(Font.font(10));
        for (Marker m : markers) {
            int i = indexOfDistance(m.distance());
            double mx = px(x[i], minX, sc, ox);
            double my = py(y[i], maxY, sc, oy);
            g.setFill(m.color());
            g.fillOval(mx - 4, my - 4, 8, 8);
            g.setFill(Color.web("#e8edf4"));
            g.fillText(m.label(), mx + 6, my - 6);
        }

        if (!Double.isNaN(cursorDistance)) {
            int i = indexOfDistance(cursorDistance);
            double cx = px(x[i], minX, sc, ox);
            double cy = py(y[i], maxY, sc, oy);
            g.setFill(CURSOR);
            g.fillOval(cx - 6, cy - 6, 12, 12);
            g.setStroke(Color.BLACK);
            g.setLineWidth(1.5);
            g.strokeOval(cx - 6, cy - 6, 12, 12);
        }

        if (colored) {
            drawLegend(g, w, h, lo, hi);
        }
    }

    private void drawLegend(GraphicsContext g, double w, double h, double lo, double hi) {
        double barW = Math.min(180, w * 0.4);
        double bx = w - barW - 14;
        double by = h - 40;
        for (int k = 0; k < barW; k++) {
            double f = k / barW;
            double v = scale == Scale.SPEED ? lo + f * (hi - lo) : (-1 + 2 * f) * hi;
            g.setStroke(color(v, lo, hi));
            g.setLineWidth(1);
            g.strokeLine(bx + k, by, bx + k, by + 8);
        }
        g.setFill(Color.web("#8b9cb3"));
        g.setFont(Font.font(10));
        g.setTextBaseline(VPos.TOP);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(lowLabel, bx, by + 10);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(highLabel, bx + barW, by + 10);
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
