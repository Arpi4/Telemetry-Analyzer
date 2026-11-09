package com.telemetry.desktop;

import com.telemetry.analyzer.analysis.DrivingEvents.BrakingZone;
import com.telemetry.analyzer.analysis.DrivingEvents.CornerApex;
import com.telemetry.analyzer.analysis.DrivingEvents.DrivingStyleMetrics;
import com.telemetry.analyzer.analysis.LapConsistency;
import com.telemetry.analyzer.analysis.LapConsistency.ExcludedLap;
import com.telemetry.analyzer.analysis.LapTrace;
import com.telemetry.analyzer.analysis.SessionLaps;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.desktop.SessionModel.LapAnalysis;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-lap view: session and lap pickers with the session's lap-time consistency, channel
 * charts over lap distance, track map coloured by speed, and the detected driving events.
 *
 * <p>The chart stack always has speed, throttle, brake and gear; steering angle, engine speed and
 * lateral / longitudinal acceleration are added when the lap carries them, and can be hidden
 * from the card header. Below the charts' minimum heights the stack scrolls; its distance axis is
 * pinned below it, so it stays in view.
 */
final class LapAnalysisView {

    /** Equal-length sectors for the theoretical best and the consistency figures. */
    private static final int SECTORS = 10;
    /** Label of the extra-channel menu, and its short form for headers too narrow for both it and the readout. */
    private static final String MENU_LABEL = "Extra channels";
    private static final String MENU_LABEL_SHORT = "More";

    /** One chart of the stack: its node there (chart plus cursor line) and its heights. */
    private record ChartRow(LineChart<Number, Number> chart, StackPane node, double minHeight, double prefHeight) {
    }

    private final SessionModel model;

    private final ListView<SessionData> sessionList = new ListView<>();
    private final ListView<LapData> lapList = new ListView<>();
    private final VBox lapsBox;
    private final GridPane summary = new GridPane();
    private final ChartUtil.Readout readout = new ChartUtil.Readout();

    private final LineChart<Number, Number> speed = ChartUtil.lineChart("Speed (km/h)", false);
    private final LineChart<Number, Number> throttle = ChartUtil.lineChart("Throttle %", false);
    private final LineChart<Number, Number> brake = ChartUtil.lineChart("Brake %", false);
    private final LineChart<Number, Number> steering = ChartUtil.lineChart("Steer (°)", false);
    private final LineChart<Number, Number> gear = ChartUtil.lineChart("Gear", true);
    private final LineChart<Number, Number> rpm = ChartUtil.lineChart("RPM", false);
    private final LineChart<Number, Number> accel = ChartUtil.signedChart("Accel (g)", false);
    private final HBox accelLegend = new HBox(10);
    private final ChartCursor cursor = new ChartCursor(this::onCursor);
    private final List<ChartRow> rows = new ArrayList<>();
    private final ChartRow steeringRow;
    private final ChartRow rpmRow;
    private final ChartRow accelRow;
    private final CheckMenuItem showSteering = new CheckMenuItem("Steering angle");
    private final CheckMenuItem showRpm = new CheckMenuItem("Engine RPM");
    private final CheckMenuItem showAccel = new CheckMenuItem("Lateral / longitudinal G");
    private final MenuButton extraChannels = new MenuButton(MENU_LABEL, null, showSteering, showRpm, showAccel);
    private final ScrollPane chartStack;
    private final NumberAxis distanceAxis = ChartUtil.distanceAxis();

    private final TrackMapView map = new TrackMapView("Select a lap to draw its track map.");
    private final GridPane metrics = new GridPane();
    private final VBox metricsBox;
    private final TabPane tables;
    private final TableView<BrakingZone> brakingTable = new TableView<>();
    private final TableView<CornerApex> cornerTable = new TableView<>();

    private final SplitPane root;
    private LapAnalysis current;

    LapAnalysisView(SessionModel model) {
        this.model = model;

        sessionList.setItems(model.sessions());
        sessionList.setCellFactory(l -> sessionCell());
        sessionList.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> showSession(s));
        sessionList.setPlaceholder(placeholder("No sessions yet. Open or drop CSV / LDX telemetry files."));
        sessionList.setPrefHeight(124);
        sessionList.setMinHeight(96);

        lapList.setCellFactory(l -> new LapCell(model, true));
        lapList.getSelectionModel().selectedItemProperty().addListener((o, a, lap) -> showLap(lap));
        lapList.setPlaceholder(placeholder("Pick a session"));
        lapList.setPrefHeight(160);
        lapList.setMinHeight(80);

        summary.getStyleClass().add("summary");
        summary.setHgap(10);
        summary.setVgap(3);
        ColumnConstraints keys = new ColumnConstraints();
        ColumnConstraints vals = new ColumnConstraints();
        vals.setHgrow(Priority.ALWAYS);
        vals.setHalignment(HPos.RIGHT);
        summary.getColumnConstraints().setAll(keys, vals);
        summary.setVisible(false);
        summary.managedProperty().bind(summary.visibleProperty());

        lapsBox = new VBox(8, lapList, summary);
        VBox.setVgrow(lapList, Priority.ALWAYS);
        VBox lapsCard = Ui.card("Laps", lapsBox);
        VBox left = new VBox(10, Ui.card("Sessions", sessionList), lapsCard);
        VBox.setVgrow(lapsCard, Priority.ALWAYS);
        left.setMinWidth(210);
        left.setPrefWidth(260);
        left.setMaxWidth(340);

        ChartUtil.fixedY(throttle, 0, 100, 25);
        ChartUtil.fixedY(brake, 0, 100, 25);
        ((NumberAxis) steering.getYAxis()).setForceZeroInRange(true);
        rows.add(row(speed, 120, 240));
        rows.add(row(throttle, 70, 150));
        rows.add(row(brake, 70, 150));
        steeringRow = row(steering, 70, 130);
        rows.add(steeringRow);
        rows.add(row(gear, 70, 150));
        rpmRow = row(rpm, 70, 130);
        rows.add(rpmRow);
        // the legend sits in a strip above the plot (desktop.css: .legend-strip), where it never
        // hides a peak of either trace; the heights include the strip, so the plot keeps its height
        accelRow = row(accel, 98, 168);
        rows.add(accelRow);
        accel.getStyleClass().add("legend-strip");
        accelLegend.getStyleClass().add("chart-legend");
        accelLegend.setMouseTransparent(true);
        accelLegend.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(accelLegend, Pos.TOP_RIGHT);
        StackPane.setMargin(accelLegend, new Insets(0, 10, 0, 0)); // right edge of the plot
        accelRow.node().getChildren().add(accelLegend);
        VBox charts = new VBox(8);
        for (ChartRow r : rows) {
            r.node().managedProperty().bind(r.node().visibleProperty());
            VBox.setVgrow(r.node(), Priority.ALWAYS);
            charts.getChildren().add(r.node());
            // the distance axis is pinned below the stack instead
            ChartUtil.showXAxis(r.chart(), false);
            r.chart().setMinHeight(r.minHeight());
            r.chart().setPrefHeight(r.prefHeight());
        }
        chartStack = ChartUtil.scrollable(charts);
        chartStack.setVisible(false);
        readout.visibleProperty().bind(chartStack.visibleProperty());
        HBox axisRow = ChartUtil.pinnedDistanceAxis(distanceAxis);
        ChartUtil.alignPinnedAxis(chartStack, distanceAxis);
        axisRow.visibleProperty().bind(chartStack.visibleProperty());
        axisRow.managedProperty().bind(axisRow.visibleProperty());
        StackPane stackWithHint = ChartUtil.withPlaceholder(chartStack, "Select a lap to plot its channels.");
        VBox chartColumn = new VBox(stackWithHint, axisRow);
        VBox.setVgrow(stackWithHint, Priority.ALWAYS);

        for (CheckMenuItem item : List.of(showSteering, showRpm, showAccel)) {
            item.setSelected(true);
            item.selectedProperty().addListener((o, a, on) -> {
                updateChartRows();
                readout.show(null, null);
            });
        }
        extraChannels.getStyleClass().add("channel-menu");
        extraChannels.setTooltip(new Tooltip("Show or hide the channels beyond speed, pedals and gear"));
        extraChannels.setMinWidth(Region.USE_PREF_SIZE);
        extraChannels.managedProperty().bind(extraChannels.visibleProperty());
        extraChannels.setVisible(false);
        VBox center = Ui.card("Channels", chartColumn, readout, Ui.spacer(), extraChannels);
        center.setMinWidth(380);
        // the header's width only changes with the window, never with the menu's label
        for (Observable o : List.of(readout.getParent().layoutBoundsProperty(), extraChannels.visibleProperty())) {
            o.addListener(x -> fitChannelMenu());
        }
        updateChartRows();

        setupTables();
        metrics.getStyleClass().add("metrics");
        metrics.setHgap(8);
        metrics.setVgap(6);
        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        metrics.getColumnConstraints().setAll(half, half);
        Label note = Ui.muted("Shares are time-weighted percentages of the lap time.");
        note.getStyleClass().add("metrics-note");
        note.setWrapText(true);
        Label noMetrics = placeholder("Select a lap to see its driving metrics.");
        for (Label l : List.of(note, noMetrics)) {
            l.managedProperty().bind(l.visibleProperty());
        }
        note.visibleProperty().bind(Bindings.isNotEmpty(metrics.getChildren()));
        noMetrics.visibleProperty().bind(Bindings.isEmpty(metrics.getChildren()));
        metricsBox = new VBox(8, metrics, note, noMetrics);
        ScrollPane metricsScroll = new ScrollPane(metricsBox);
        metricsScroll.setFitToWidth(true);
        metricsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        tables = Ui.cardTabs(
                Ui.tab("Metrics", metricsScroll),
                Ui.tab("Braking zones", brakingTable),
                Ui.tab("Corners", cornerTable));
        SplitPane right = new SplitPane(Ui.card("Track map", map), tables);
        right.setOrientation(Orientation.VERTICAL);
        right.setDividerPositions(0.54);
        right.setMinWidth(340); // the seven braking-zone columns without sideways scrolling
        right.setPrefWidth(440);
        right.setMaxWidth(560);
        // the metrics get the room for all their tiles (or whole rows of them) below the map
        Runnable fit = Ui.fitBottom(right, 0.45, this::metricsHeight);
        metricsBox.heightProperty().addListener((o, a, h) -> fit.run());

        root = new SplitPane(left, center, right);
        root.getStyleClass().add("view");
        root.setDividerPositions(0.175, 0.71);
    }

    /** Height of the tab card that shows every metric tile, or whole rows of them within {@code max}. */
    private double metricsHeight(double max) {
        double chrome = Ui.tabChrome(tables);
        if (Double.isNaN(chrome) || metrics.getChildren().isEmpty() || metricsBox.getHeight() <= 0) {
            return Double.NaN;
        }
        if (chrome + metricsBox.getHeight() <= max) {
            return chrome + metricsBox.getHeight() + 1;
        }
        Region tile = (Region) metrics.getChildren().getFirst();
        return Ui.wholeRows(max, chrome + metrics.snappedTopInset(), tile.getHeight(), metrics.getVgap(),
                (metrics.getChildren().size() + 1) / 2) + 1;
    }

    Node node() {
        return root;
    }

    Node trackMap() {
        return map;
    }

    /** Select the session and its fastest lap. */
    void select(SessionData session) {
        sessionList.getSelectionModel().select(session);
        model.fastestLap(session).ifPresent(l -> lapList.getSelectionModel().select(l));
        revealSelectedLap();
    }

    private void showSession(SessionData s) {
        if (s == null) {
            lapList.getItems().clear();
            summary.setVisible(false);
            return;
        }
        lapList.setItems(FXCollections.observableArrayList(s.laps()));
        summary.getChildren().clear();
        summaryRow(0, "Complete laps", model.completeLaps(s).size() + " / " + s.laps().size(), null);
        model.fastestLap(s).ifPresent(f -> summaryRow(1, "Fastest lap",
                Formats.lapTime(model.analyze(s, f).trace().lapTime()), f.lapId()));
        SessionLaps.theoreticalBest(s, SECTORS).ifPresent(tb -> summaryRow(2, "Theoretical best",
                Formats.lapTime(tb.lapTime()), "Best of each of " + SECTORS + " sectors across the complete laps"));
        SessionLaps.consistency(s, SECTORS).ifPresentOrElse(c -> consistencyRows(3, c), () -> tooFewFlyingLaps(3, s));
        summary.setVisible(true);
        LapData initial = model.fastestLap(s).orElse(s.laps().isEmpty() ? null : s.laps().getFirst());
        if (initial != null) {
            lapList.getSelectionModel().select(initial);
            revealSelectedLap();
        }
    }

    /**
     * Scrolls the lap list just far enough to show the selected lap in full: with the session
     * summary below it, the list of a small window holds only a few laps. (ListView.scrollTo
     * would move the lap to the top, hiding the laps above it even when all of them fit.)
     */
    private void revealSelectedLap() {
        int i = lapList.getSelectionModel().getSelectedIndex();
        if (i < 0) {
            return;
        }
        // the list's height with the new summary, and its cells for the new laps
        lapsBox.applyCss();
        lapsBox.layout();
        if (lapList.lookup(".virtual-flow") instanceof VirtualFlow<?> flow) {
            flow.scrollTo(i);
        }
    }

    private void summaryRow(int row, String key, String value, String tooltip) {
        Label k = Ui.muted(key);
        Label v = new Label(value);
        v.getStyleClass().add("summary-value");
        if (tooltip != null) {
            k.setTooltip(new Tooltip(tooltip));
            v.setTooltip(new Tooltip(tooltip));
        }
        summary.addRow(row, k, v);
    }

    /**
     * Lap-time consistency of the flying laps (the complete laps without out-laps and in-laps),
     * below the fastest lap and theoretical best, followed by the laps left out.
     */
    private void consistencyRows(int row, LapConsistency c) {
        String flying = c.lapCount() + " flying laps";
        summaryCaption(row++, "Consistency · " + flying, "Lap-to-lap variation of the " + flying
                + ": the complete laps without out-laps and in-laps (" + String.join(", ", c.lapIds()) + ")");
        summaryRow(row++, "Std deviation", Formats.number(c.lapTimeStdDev(), 3) + " s",
                "Sample standard deviation (n − 1) of the flying lap times\nmean "
                        + Formats.lapTime(c.meanLapTime()) + ", median " + Formats.lapTime(c.medianLapTime()));
        summaryRow(row++, "CV", Formats.number(c.coefficientOfVariationPercent(), 2) + " %",
                "Coefficient of variation: standard deviation / mean flying lap time");
        summaryRow(row++, "Spread", Formats.number(c.spread(), 3) + " s", "Slowest minus fastest flying lap");
        int worst = c.leastConsistentSector();
        if (worst > 0) {
            StringBuilder sectors = new StringBuilder("Sector whose time varies most from lap to lap (± its standard "
                    + "deviation), of " + SECTORS + " equal-length sectors. Standard deviation per sector:");
            for (int k = 0; k < c.sectorStdDevs().length; k++) {
                sectors.append(String.format(Locale.ROOT, "%nS%-3d %.3f s", k + 1, c.sectorStdDevs()[k]));
            }
            // as wide as a lap time, so the row fits wherever the theoretical best does
            summaryRow(row++, "Least consistent",
                    "S" + worst + " ±" + Formats.number(c.sectorStdDevs()[worst - 1], 2), sectors.toString());
        }
        excludedRows(row, c.excludedLaps());
    }

    /**
     * With two or more complete laps but fewer than two flying ones the figures are not shown;
     * the laps left out are still listed, so it is clear why.
     */
    private void tooFewFlyingLaps(int row, SessionData s) {
        // the laps SessionLaps.consistency works over: complete, with a lap time
        long timed = model.completeLaps(s).stream().filter(l -> LapTrace.of(l).lapTime() > 0).count();
        if (timed < 2) {
            return;
        }
        List<ExcludedLap> excluded = SessionLaps.excludedLaps(s);
        int flying = (int) timed - excluded.size();
        summaryCaption(row++, "Consistency · " + flying + (flying == 1 ? " flying lap" : " flying laps"),
                "Lap-to-lap variation needs at least two flying laps: complete laps that are neither an out-lap "
                        + "nor an in-lap");
        Label note = Ui.muted("Needs at least 2 flying laps");
        note.setWrapText(true);
        summary.add(note, 0, row++, 2, 1);
        excludedRows(row, excluded);
    }

    /** Group heading of the summary, in the style of the tile captions. */
    private void summaryCaption(int row, String text, String tooltip) {
        Label caption = new Label(text.toUpperCase(Locale.ROOT));
        caption.getStyleClass().add("summary-caption");
        caption.setMaxWidth(Double.MAX_VALUE);
        caption.setTooltip(new Tooltip(tooltip));
        GridPane.setMargin(caption, new Insets(3, 0, 0, 0));
        summary.add(caption, 0, row, 2, 1);
    }

    /**
     * The complete laps the consistency figures leave out, one row each ("Out-lap excluded  lap-1":
     * a single lap id is as narrow as the lap times above it); more than two are counted in one
     * row. The tooltip gives each lap's reason, entry and exit speed, and the rule.
     */
    private void excludedRows(int row, List<ExcludedLap> excluded) {
        if (excluded.isEmpty()) {
            return;
        }
        StringBuilder tip = new StringBuilder("Left out of the consistency figures (the fastest lap and the "
                + "theoretical best still count them):");
        for (ExcludedLap e : excluded) {
            tip.append(String.format(Locale.ROOT, "\n%s  %s, starts at %.0f km/h, ends at %.0f km/h",
                    e.lapId(), e.reason(), e.entrySpeed(), e.exitSpeed()));
        }
        int percent = (int) Math.round(LapConsistency.PIT_SPEED_RATIO * 100);
        tip.append("\nOut-lap: starts below ").append(percent).append(" % of the median entry speed of the other")
                .append(" complete laps (pit exit).\nIn-lap: ends below ").append(percent)
                .append(" % of their median exit speed (pit entry).\nA lap that starts where an in-lap ends is an")
                .append(" out-lap, and the other way round.");
        String tooltip = tip.toString();
        if (excluded.size() > 2) {
            summaryRow(row, "Laps excluded", String.valueOf(excluded.size()), tooltip);
            return;
        }
        for (int i = 0; i < excluded.size(); i++) {
            ExcludedLap e = excluded.get(i);
            summaryRow(row + i, reasonLabel(e.reason()) + " excluded", e.lapId(), tooltip);
        }
    }

    /** "Out-lap", "In-lap" or "Out/in-lap", short enough for the key column. */
    private static String reasonLabel(String reason) {
        return switch (reason) {
            case LapConsistency.OUT_LAP -> "Out-lap";
            case LapConsistency.IN_LAP -> "In-lap";
            case LapConsistency.OUT_AND_IN_LAP -> "Out/in-lap";
            default -> reason;
        };
    }

    private void showLap(LapData lap) {
        SessionData s = sessionList.getSelectionModel().getSelectedItem();
        if (lap == null || s == null) {
            return;
        }
        current = model.analyze(s, lap);
        chartStack.setVisible(true);
        LapTrace t = current.trace();
        ExtraChannels ch = current.channels();
        for (ChartRow r : rows) {
            ChartUtil.xRange(r.chart(), t.lapLength());
        }
        ChartUtil.xRange(distanceAxis, t.lapLength());
        ChartUtil.show(speed, ChartUtil.series("Speed", t.distance(), t.speed()));
        ChartUtil.show(throttle, ChartUtil.series("Throttle", t.distance(), t.throttle()));
        ChartUtil.show(brake, ChartUtil.series("Brake", t.distance(), t.brake()));
        ChartUtil.show(gear, ChartUtil.series("Gear", t.distance(), t.gear()));
        double topGear = java.util.Arrays.stream(t.gear()).max().orElse(6);
        ChartUtil.fixedY(gear, Math.min(0, java.util.Arrays.stream(t.gear()).min().orElse(0)), topGear + 1, 1);
        plot(steering, "Steering", t.distance(), ch.steering());
        plot(rpm, "RPM", t.distance(), ch.rpm());
        // lateral first, so with both channels it takes the colour of the other charts and the
        // longitudinal trace (derived from speed for coarse logs, see QuantisedAcceleration) is drawn on top
        List<XYChart.Series<Number, Number>> g = new ArrayList<>();
        accelLegend.getChildren().clear();
        double gMin = 0;
        double gMax = 0;
        for (String name : List.of("lateral", "longitudinal")) {
            double[] values = name.equals("lateral") ? ch.lateralG() : ch.longitudinalG();
            if (values != null) {
                accelLegend.getChildren().add(Ui.legend(name, "dot-series" + g.size()));
                g.add(ChartUtil.series(name, t.distance(), values));
                gMin = Math.min(gMin, java.util.Arrays.stream(values).min().orElse(0));
                gMax = Math.max(gMax, java.util.Arrays.stream(values).max().orElse(0));
            }
        }
        ChartUtil.signedY(accel, gMin, gMax);
        accel.getData().setAll(g);
        updateChartRows();

        map.setTrack(current.outline());
        if (!current.outline().isEmpty()) {
            map.setValues(at(current.outline().distance(), t.distance(), t.speed()), TrackMapView.Scale.SPEED,
                    "slow", "fast");
        }
        List<TrackMapView.Marker> markers = new ArrayList<>();
        for (BrakingZone z : current.events().brakingZones()) {
            markers.add(new TrackMapView.Marker(z.startDistance(), "B" + z.index(), Color.web("#ff3d2e")));
        }
        map.setMarkers(markers);

        brakingTable.setItems(FXCollections.observableArrayList(current.events().brakingZones()));
        cornerTable.setItems(FXCollections.observableArrayList(current.events().corners()));
        fillMetrics(current.events().metrics(), current.events().brakingZones().size(),
                current.events().corners().size());
    }

    private void onCursor(double distance) {
        cursor.show(distance);
        map.setCursorDistance(distance);
        if (current == null || Double.isNaN(distance)) {
            readout.show(null, null);
            return;
        }
        LapTrace t = current.trace();
        int i = Math.clamp(ChartUtil.indexAtOrAfter(t.distance(), distance), 0, t.size() - 1);
        String[] text = readoutTexts(i);
        readout.show(text[0], text[1]);
    }

    /** Full and compact readout text of sample {@code i} of the current lap. */
    private String[] readoutTexts(int i) {
        LapTrace t = current.trace();
        String time = Formats.lapTime(t.elapsed()[i]).replace("n/a", "0:00.000");
        // fixed-width fields keep the text still while the cursor moves; the compact form drops the time
        String full = String.format(Locale.ROOT, "%4.0f m · %s · %3.0f km/h · throttle %3.0f %% · brake %3.0f %% · gear %d",
                t.distance()[i], time, t.speed()[i], t.throttle()[i], t.brake()[i], (int) t.gear()[i]);
        String compact = String.format(Locale.ROOT, "%4.0f m · %3.0f km/h · thr %3.0f%% · brk %3.0f%% · gear %d",
                t.distance()[i], t.speed()[i], t.throttle()[i], t.brake()[i], (int) t.gear()[i]);
        // the extra channels on shown charts go to a second line
        ExtraChannels ch = current.channels();
        List<String> extraFull = new ArrayList<>();
        List<String> extraCompact = new ArrayList<>();
        if (steeringRow.node().isVisible()) {
            extraFull.add(String.format(Locale.ROOT, "steering %4.0f°", ch.steering()[i]));
            extraCompact.add(String.format(Locale.ROOT, "str %4.0f°", ch.steering()[i]));
        }
        if (rpmRow.node().isVisible()) {
            String engine = String.format(Locale.ROOT, "%5.0f rpm", ch.rpm()[i]);
            extraFull.add(engine);
            extraCompact.add(engine);
        }
        if (accelRow.node().isVisible()) {
            List<String> g = new ArrayList<>();
            if (ch.lateralG() != null) {
                extraFull.add(String.format(Locale.ROOT, "lateral %+5.2f g", ch.lateralG()[i]));
                g.add(String.format(Locale.ROOT, "lat %+5.2f", ch.lateralG()[i]));
            }
            if (ch.longitudinalG() != null) {
                extraFull.add(String.format(Locale.ROOT, "longitudinal %+5.2f g", ch.longitudinalG()[i]));
                g.add(String.format(Locale.ROOT, "lon %+5.2f", ch.longitudinalG()[i]));
            }
            extraCompact.add(String.join(" ", g) + " g");
        }
        if (!extraFull.isEmpty()) {
            full += "\n" + String.join(" · ", extraFull);
            compact += "\n" + String.join(" · ", extraCompact);
        }
        return new String[] {full, compact};
    }

    /**
     * Shortens the extra-channel menu's label when the card header could not otherwise show the
     * compact readout in full (narrow windows, e.g. 1180 px wide), and restores it when there
     * is room for both. The compact readout of every sample is as wide as that of the first or
     * the last one (fixed-width fields), so the label does not change while the cursor moves.
     */
    private void fitChannelMenu() {
        if (current == null || !extraChannels.isVisible() || !(readout.getParent() instanceof HBox head)
                || !(extraChannels.lookup(".label") instanceof Labeled label)) {
            return;
        }
        Font font = label.getFont();
        double saved = Ui.textWidth(MENU_LABEL, font) - Ui.textWidth(MENU_LABEL_SHORT, font);
        boolean isLong = MENU_LABEL.equals(extraChannels.getText());
        // what the readout could take next to the short label, from the header's width and the
        // preferred widths of the rest (the title and the menu are never narrower, the spacer
        // takes what the readout leaves); the widths of the last layout may predate the label
        double rest = head.snappedLeftInset() + head.snappedRightInset()
                + extraChannels.prefWidth(-1) - (isLong ? saved : 0);
        int managed = 0;
        for (Node n : head.getChildren()) {
            if (n.isManaged()) {
                managed++;
                rest += n == readout || n == extraChannels ? 0 : n.prefWidth(-1);
            }
        }
        double room = head.getWidth() - rest - head.getSpacing() * (managed - 1);
        int last = current.trace().size() - 1;
        double need = Math.max(readout.readingWidth(readoutTexts(0)[1]), readout.readingWidth(readoutTexts(last)[1]));
        String text = room - saved >= need - 0.5 ? MENU_LABEL : MENU_LABEL_SHORT;
        if (!text.equals(extraChannels.getText())) {
            extraChannels.setText(text);
        }
    }

    private ChartRow row(LineChart<Number, Number> chart, double minHeight, double prefHeight) {
        return new ChartRow(chart, cursor.attach(chart), minHeight, prefHeight);
    }

    /** Shows {@code values} as the chart's only series, or nothing when the lap lacks the channel. */
    private static void plot(LineChart<Number, Number> chart, String name, double[] x, double[] values) {
        if (values == null) {
            chart.getData().clear();
        } else {
            ChartUtil.show(chart, ChartUtil.series(name, x, values));
        }
    }

    /** Shows the extra charts the current lap has data for and the header menu does not hide. */
    private void updateChartRows() {
        ExtraChannels ch = current == null ? null : current.channels();
        boolean hasSteering = ch != null && ch.steering() != null;
        boolean hasRpm = ch != null && ch.rpm() != null;
        boolean hasAccel = ch != null && ch.hasAccelerations();
        showSteering.setVisible(hasSteering);
        showRpm.setVisible(hasRpm);
        showAccel.setVisible(hasAccel);
        extraChannels.setVisible(hasSteering || hasRpm || hasAccel);
        steeringRow.node().setVisible(hasSteering && showSteering.isSelected());
        rpmRow.node().setVisible(hasRpm && showRpm.isSelected());
        accelRow.node().setVisible(hasAccel && showAccel.isSelected());
        readout.twoLines(steeringRow.node().isVisible() || rpmRow.node().isVisible() || accelRow.node().isVisible());
        fitChannelMenu();
    }

    private void fillMetrics(DrivingStyleMetrics m, int zones, int corners) {
        metrics.getChildren().clear();
        String[][] rows = {
                {"Lap time", Formats.lapTime(m.lapTime())},
                {"Lap length", Formats.number(m.lapLength(), 0) + " m"},
                {"Average speed", Formats.number(m.averageSpeed(), 1) + " km/h"},
                {"Top speed", Formats.number(m.maxSpeed(), 1) + " km/h"},
                {"Full throttle", Formats.number(m.fullThrottlePercent(), 1) + " %"},
                {"Braking", Formats.number(m.brakingPercent(), 1) + " %"},
                {"Coasting", Formats.number(m.coastingPercent(), 1) + " %"},
                {"Pedal overlap", Formats.number(m.pedalOverlapPercent(), 1) + " %"},
                {"Gear changes", String.valueOf(m.gearChanges())},
                {"Zones / corners", zones + " / " + corners},
        };
        for (int r = 0; r < rows.length; r++) {
            Label v = new Label(rows[r][1]);
            VBox tile = Ui.tile(rows[r][0], v, null);
            tile.getStyleClass().add("metric");
            metrics.add(tile, r % 2, r / 2);
        }
    }

    private void setupTables() {
        brakingTable.getColumns().setAll(List.of(
                Ui.col("#", z -> String.valueOf(z.index())),
                Ui.col("Start (m)", z -> Formats.number(z.startDistance(), 0)),
                Ui.col("Length (m)", z -> Formats.number(z.endDistance() - z.startDistance(), 0)),
                Ui.col("Entry (km/h)", z -> Formats.number(z.entrySpeed(), 0)),
                Ui.col("Min (km/h)", z -> Formats.number(z.minSpeed(), 0)),
                Ui.col("Time (s)", z -> Formats.number(z.duration(), 2)),
                Ui.col("Peak (%)", z -> Formats.number(z.peakBrake(), 0))));
        Ui.fitColumns(brakingTable);
        brakingTable.setPlaceholder(placeholder("No braking zones"));
        brakingTable.getSelectionModel().selectedItemProperty().addListener((o, a, z) -> {
            if (z != null) {
                onCursor(z.startDistance());
            }
        });

        cornerTable.getColumns().setAll(List.of(
                Ui.col("#", (CornerApex c) -> String.valueOf(c.index())),
                Ui.col("Apex (m)", c -> Formats.number(c.distance(), 0)),
                Ui.col("Min (km/h)", c -> Formats.number(c.minSpeed(), 0)),
                Ui.col("Entry (km/h)", c -> Formats.number(c.entrySpeed(), 0)),
                Ui.col("Exit (km/h)", c -> Formats.number(c.exitSpeed(), 0)),
                Ui.col("Gear", c -> String.valueOf(c.gear()))));
        Ui.fitColumns(cornerTable);
        cornerTable.setPlaceholder(placeholder("No corners detected"));
        cornerTable.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> {
            if (c != null) {
                onCursor(c.distance());
            }
        });
    }

    private ListCell<SessionData> sessionCell() {
        return new ListCell<>() {
            private final Label name = new Label();
            private final Label meta = Ui.muted("");
            private final VBox box = new VBox(0, name, meta);

            {
                name.getStyleClass().add("session-name");
                meta.getStyleClass().add("session-meta");
            }

            @Override
            protected void updateItem(SessionData s, boolean empty) {
                super.updateItem(s, empty);
                setText(null);
                if (empty || s == null) {
                    setGraphic(null);
                    return;
                }
                name.setText(s.sourceFile());
                meta.setText(s.laps().size() + " laps  ·  " + model.completeLaps(s).size() + " complete");
                setGraphic(box);
            }
        };
    }

    private static Label placeholder(String text) {
        Label l = Ui.muted(text);
        l.setWrapText(true);
        l.getStyleClass().add("placeholder-text");
        return l;
    }

    /** Channel values evaluated at the given distances (outline samples). */
    static double[] at(double[] targetDistance, double[] sourceDistance, double[] values) {
        return com.telemetry.analyzer.analysis.Interpolation.resample(sourceDistance, values, targetDistance);
    }
}
