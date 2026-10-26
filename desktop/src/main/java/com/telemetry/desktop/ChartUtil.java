package com.telemetry.desktop;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Line chart factory and series construction. Series are thinned with min/max bucketing so that
 * short peaks (brake spikes, gear changes) survive even when 20 000+ samples are reduced to a
 * few thousand chart points.
 */
final class ChartUtil {

    /** Buckets per series; each bucket contributes its minimum and maximum. */
    static final int MAX_BUCKETS = 1_200;

    private static final String READOUT_HINT = "Hover the charts to read values";

    private ChartUtil() {
    }

    static LineChart<Number, Number> lineChart(String yLabel, boolean showXLabel) {
        NumberAxis x = new NumberAxis();
        x.setAnimated(false);
        x.setAutoRanging(false);
        x.setLabel(showXLabel ? "Lap distance (m)" : null);
        x.setTickLabelsVisible(showXLabel);
        x.setMinorTickVisible(false);
        x.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number value) {
                // the upper bound is the exact lap length; only round ticks get a label
                double v = value.doubleValue();
                double tick = x.getTickUnit();
                return Math.abs(v / tick - Math.rint(v / tick)) < 1e-6 ? String.format(Locale.ROOT, "%.0f", v) : "";
            }

            @Override
            public Number fromString(String s) {
                return Double.parseDouble(s);
            }
        });
        NumberAxis y = new NumberAxis();
        y.setLabel(yLabel);
        y.setAnimated(false);
        y.setForceZeroInRange(false);
        y.setMinorTickVisible(false);
        y.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number value) {
                // as many decimals as the tick unit has, with a decimal point like the rest of the UI
                double tick = y.getTickUnit();
                int decimals = Math.max(0, BigDecimal.valueOf(tick).stripTrailingZeros().scale());
                double v = Math.abs(value.doubleValue()) < tick * 1e-6 ? 0 : value.doubleValue(); // no "-0.0"
                return String.format(Locale.ROOT, "%." + Math.min(decimals, 3) + "f", v);
            }

            @Override
            public Number fromString(String s) {
                return Double.parseDouble(s);
            }
        });
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.setHorizontalGridLinesVisible(true);
        chart.setVerticalGridLinesVisible(true);
        chart.getStyleClass().add("telemetry-chart");
        chart.setMinHeight(70);
        return chart;
    }

    /**
     * Wraps a stack of charts: the charts shrink with the window down to their minimum heights,
     * below that the stack scrolls instead of squeezing them further.
     */
    static ScrollPane scrollable(Region charts) {
        ScrollPane pane = new ScrollPane(charts);
        pane.setFitToWidth(true);
        pane.setFitToHeight(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return pane;
    }

    /** Shows {@code text} instead of the charts while they are hidden (nothing loaded yet). */
    static StackPane withPlaceholder(Region charts, String text) {
        Label hint = new Label(text);
        hint.getStyleClass().addAll("muted", "placeholder-text");
        hint.setWrapText(true);
        hint.visibleProperty().bind(charts.visibleProperty().not());
        return new StackPane(charts, hint);
    }

    /**
     * Cursor readout in the chart card header. It shows the full text when the header has room
     * for it and the compact form otherwise, so small windows still show every value.
     */
    static final class Readout extends Label {
        private String full = READOUT_HINT;
        private String compact = READOUT_HINT;

        Readout() {
            getStyleClass().add("readout");
            setMinWidth(0);
            show(null, null);
        }

        /** {@code null} shows a usage hint instead. */
        void show(String full, String compact) {
            getStyleClass().remove("idle");
            if (full == null) {
                getStyleClass().add("idle");
            }
            this.full = full == null ? READOUT_HINT : full;
            this.compact = full == null ? READOUT_HINT : compact;
            pickText();
            requestLayout();
        }

        /** Asks for the room of the full text, whichever form is shown. */
        @Override
        protected double computePrefWidth(double height) {
            return Ui.textWidth(full, getFont()) + snappedLeftInset() + snappedRightInset();
        }

        @Override
        protected void layoutChildren() {
            pickText();
            super.layoutChildren();
        }

        private void pickText() {
            setText(getWidth() >= computePrefWidth(-1) - 0.5 ? full : compact);
        }
    }

    static void fixedY(LineChart<Number, Number> chart, double lower, double upper, double tick) {
        NumberAxis y = (NumberAxis) chart.getYAxis();
        y.setAutoRanging(false);
        y.setLowerBound(lower);
        y.setUpperBound(upper);
        y.setTickUnit(tick);
    }

    static void xRange(LineChart<Number, Number> chart, double length) {
        NumberAxis x = (NumberAxis) chart.getXAxis();
        double upper = Math.max(1, length);
        x.setLowerBound(0);
        x.setUpperBound(upper);
        x.setTickUnit(niceTick(upper / 10));
    }

    @SafeVarargs
    static void show(LineChart<Number, Number> chart, XYChart.Series<Number, Number>... series) {
        chart.getData().setAll(series);
    }

    static XYChart.Series<Number, Number> series(String name, double[] x, double[] y) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(name);
        series.setData(decimate(x, y));
        return series;
    }

    static ObservableList<XYChart.Data<Number, Number>> decimate(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        ObservableList<XYChart.Data<Number, Number>> data = FXCollections.observableArrayList();
        if (n == 0) {
            return data;
        }
        if (n <= 2 * MAX_BUCKETS) {
            for (int i = 0; i < n; i++) {
                data.add(new XYChart.Data<>(x[i], y[i]));
            }
            return data;
        }
        double bucket = (double) n / MAX_BUCKETS;
        for (int b = 0; b < MAX_BUCKETS; b++) {
            int from = (int) (b * bucket);
            int to = Math.min(n, (int) ((b + 1) * bucket));
            int lo = from;
            int hi = from;
            for (int i = from; i < to; i++) {
                if (y[i] < y[lo]) {
                    lo = i;
                }
                if (y[i] > y[hi]) {
                    hi = i;
                }
            }
            int first = Math.min(lo, hi);
            int second = Math.max(lo, hi);
            data.add(new XYChart.Data<>(x[first], y[first]));
            if (second != first) {
                data.add(new XYChart.Data<>(x[second], y[second]));
            }
        }
        data.add(new XYChart.Data<>(x[n - 1], y[n - 1]));
        return data;
    }

    static double niceTick(double raw) {
        if (raw <= 0) {
            return 1;
        }
        double exp = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / exp;
        double nice = f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10;
        return nice * exp;
    }

    /** First index with values[i] >= target in an ascending array (binary search). */
    static int indexAtOrAfter(double[] values, double target) {
        int lo = 0;
        int hi = values.length - 1;
        if (hi < 0) {
            return -1;
        }
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (values[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
