package com.telemetry.desktop;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.ValueAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
        NumberAxis y = new NumberAxis();
        y.setForceZeroInRange(false);
        y.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number value) {
                return yTickLabel(value.doubleValue(), y.getTickUnit());
            }

            @Override
            public Number fromString(String s) {
                return Double.parseDouble(s);
            }
        });
        return chart(yLabel, y, showXLabel);
    }

    /**
     * Line chart for a signed channel such as acceleration, with the y range set by
     * {@link #signedY} instead of the auto range.
     */
    static LineChart<Number, Number> signedChart(String yLabel, boolean showXLabel) {
        return chart(yLabel, new SignedAxis(), showXLabel);
    }

    private static LineChart<Number, Number> chart(String yLabel, ValueAxis<Number> y, boolean showXLabel) {
        NumberAxis x = distanceAxis();
        y.setLabel(yLabel);
        y.setAnimated(false);
        y.setMinorTickVisible(false);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.setHorizontalGridLinesVisible(true);
        chart.setVerticalGridLinesVisible(true);
        chart.getStyleClass().add("telemetry-chart");
        chart.setMinHeight(70);
        showXAxis(chart, showXLabel);
        return chart;
    }

    /**
     * Lap-distance axis, ranged by {@link #xRange}. Every chart has one; a stack of charts can
     * also show one on its own below the stack ({@link #pinnedDistanceAxis}).
     */
    static NumberAxis distanceAxis() {
        NumberAxis x = new NumberAxis();
        x.setAnimated(false);
        x.setAutoRanging(false);
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
        return x;
    }

    /**
     * Distance axis of a scrolling chart stack, for the row below its scroll pane, where it stays
     * in view while the charts scroll. The row's padding (desktop.css .pinned-axis) lines it up
     * with the plots: the y axis width on the left and the chart padding on the right; {@link
     * #alignPinnedAxis} adds the width of the stack's scroll bar while that is shown.
     */
    static HBox pinnedDistanceAxis(NumberAxis axis) {
        axis.setSide(Side.BOTTOM);
        axis.setLabel("Lap distance (m)");
        axis.setTickLabelsVisible(true);
        axis.setMouseTransparent(true);
        HBox row = new HBox(axis);
        HBox.setHgrow(axis, Priority.ALWAYS);
        row.getStyleClass().add("pinned-axis");
        row.setMinHeight(Region.USE_PREF_SIZE); // the stack above shrinks first
        return row;
    }

    /** Keeps a {@link #pinnedDistanceAxis} as wide as the plots of {@code stack}, scroll bar or not. */
    static void alignPinnedAxis(ScrollPane stack, NumberAxis axis) {
        Runnable align = () -> {
            double bar = stack.getWidth() - stack.snappedLeftInset() - stack.snappedRightInset()
                    - stack.getViewportBounds().getWidth();
            Insets margin = new Insets(0, Math.max(0, bar), 0, 0);
            if (!margin.equals(HBox.getMargin(axis))) {
                HBox.setMargin(axis, margin);
            }
        };
        stack.viewportBoundsProperty().addListener((o, a, b) -> align.run());
        stack.widthProperty().addListener((o, a, b) -> align.run());
    }

    /** As many decimals as the tick unit has, with a decimal point like the rest of the UI. */
    private static String yTickLabel(double value, double tick) {
        int decimals = Math.max(0, BigDecimal.valueOf(tick).stripTrailingZeros().scale());
        double v = Math.abs(value) < tick * 1e-6 ? 0 : value; // no "-0.0"
        return String.format(Locale.ROOT, "%." + Math.min(decimals, 3) + "f", v);
    }

    /**
     * Y axis of a {@link #signedChart}: fixed bounds, with ticks, grid lines and labels only at
     * the multiples of the tick unit, counted from zero, and none on the bounds themselves.
     * NumberAxis always puts a tick on each bound; on a short chart its label thinning then
     * keeps the bound labels and drops zero.
     */
    private static final class SignedAxis extends ValueAxis<Number> {
        private double tickUnit = 1;

        SignedAxis() {
            setAutoRanging(false);
            setRange(-1, 1, 1); // not ValueAxis' 0..100, which would be 101 ticks of 1
        }

        void setRange(double lower, double upper, double tick) {
            tickUnit = tick;
            setLowerBound(lower);
            setUpperBound(upper);
            invalidateRange(); // also when only the tick unit changed
            requestAxisLayout();
        }

        @Override
        protected List<Number> calculateTickValues(double length, Object range) {
            double[] bounds = (double[]) range;
            List<Number> ticks = new ArrayList<>();
            long first = (long) Math.ceil(bounds[0] / tickUnit - 1e-9);
            long last = (long) Math.floor(bounds[1] / tickUnit + 1e-9);
            for (long k = first; k <= last && ticks.size() < 50; k++) {
                ticks.add(k * tickUnit);
            }
            return ticks;
        }

        @Override
        protected List<Number> calculateMinorTickMarks() {
            return List.of();
        }

        @Override
        protected Object getRange() {
            double length = getSide() != null && getSide().isHorizontal() ? getWidth() : getHeight();
            return new double[] {getLowerBound(), getUpperBound(),
                    calculateNewScale(length, getLowerBound(), getUpperBound())};
        }

        @Override
        protected void setRange(Object range, boolean animate) { // only called while auto-ranging
            double[] bounds = (double[]) range;
            setLowerBound(bounds[0]);
            setUpperBound(bounds[1]);
            currentLowerBound.set(bounds[0]);
            setScale(bounds[2]);
        }

        @Override
        protected String getTickMarkLabel(Number value) {
            return yTickLabel(value.doubleValue(), tickUnit);
        }

        @Override
        public Number toRealValue(double value) {
            return value;
        }
    }

    /** Distance tick labels and axis title; a stack of charts shows them on its bottom chart only. */
    static void showXAxis(LineChart<Number, Number> chart, boolean show) {
        NumberAxis x = (NumberAxis) chart.getXAxis();
        x.setLabel(show ? "Lap distance (m)" : null);
        x.setTickLabelsVisible(show);
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
     * for it and the compact form otherwise, so small windows still show every value. Both forms
     * may have a second line; {@link #twoLines} keeps the room for it while the hint is shown.
     */
    static final class Readout extends Label {
        /** Font family of a reading, as in desktop.css (.readout); the hint uses the UI font. */
        static final String READING_FONT = "Consolas";

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

        /**
         * Reserves the height of two text lines (see desktop.css), so the card header does not
         * change height, and the charts below it do not move, when the readout switches between
         * the one-line hint and a two-line reading.
         */
        void twoLines(boolean on) {
            getStyleClass().remove("two-lines");
            if (on) {
                getStyleClass().add("two-lines");
            }
        }

        /** Asks for the room of the full text, whichever form is shown. */
        @Override
        protected double computePrefWidth(double height) {
            return Ui.textWidth(full, getFont()) + snappedLeftInset() + snappedRightInset();
        }

        /**
         * Width needed to show {@code reading} without clipping, also while the hint (in another
         * font) is shown: readings use {@link #READING_FONT}, at the size of the current font.
         */
        double readingWidth(String reading) {
            return Ui.textWidth(reading, Font.font(READING_FONT, getFont().getSize()))
                    + snappedLeftInset() + snappedRightInset();
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

    /**
     * Y range of a {@link #signedChart}: the data and zero, widened to whole steps of about a
     * quarter of the range (1 g for a race car's ±2–3 g), so the traces fill the plot. Grid lines
     * and labels sit at the multiples of the step counted from zero, or of twice the step when
     * the range has more than three steps (±3 g: lines at -2, 0 and 2 g). That is at most four
     * labels, which a 70 px plot holds at the print font too, so the axis never has to thin them
     * out and zero always keeps its label. The auto range of a NumberAxis would instead pick 5 g
     * steps at that height and leave the traces in the middle half of the plot.
     */
    static void signedY(LineChart<Number, Number> chart, double min, double max) {
        double lo = Math.min(0, min);
        double hi = Math.max(0, max);
        if (!(hi > lo)) { // no data, flat zero or NaN
            lo = -1;
            hi = 1;
        }
        double step = niceTick((hi - lo) / 4); // 3 to 7 steps once rounded out
        lo = Math.floor(lo / step + 1e-9) * step; // 1e-9: 1.6 / 0.2 is 8.000000000000002
        hi = Math.ceil(hi / step - 1e-9) * step;
        long steps = Math.round((hi - lo) / step);
        ((SignedAxis) chart.getYAxis()).setRange(lo, hi, steps > 3 ? 2 * step : step);
    }

    static void xRange(LineChart<Number, Number> chart, double length) {
        xRange((NumberAxis) chart.getXAxis(), length);
    }

    static void xRange(NumberAxis x, double length) {
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
