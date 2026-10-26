package com.telemetry.desktop;

import com.telemetry.analyzer.analysis.DrivingEvents.BrakingZone;
import com.telemetry.analyzer.analysis.DrivingEvents.CornerApex;
import com.telemetry.analyzer.analysis.DrivingEvents.DrivingStyleMetrics;
import com.telemetry.analyzer.analysis.LapTrace;
import com.telemetry.analyzer.analysis.SessionLaps;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.desktop.SessionModel.LapAnalysis;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-lap view: session and lap pickers, channel charts over lap distance, track map coloured
 * by speed, and the detected driving events.
 */
final class LapAnalysisView {

    private final SessionModel model;

    private final ListView<SessionData> sessionList = new ListView<>();
    private final ListView<LapData> lapList = new ListView<>();
    private final GridPane summary = new GridPane();
    private final ChartUtil.Readout readout = new ChartUtil.Readout();

    private final LineChart<Number, Number> speed = ChartUtil.lineChart("Speed (km/h)", false);
    private final LineChart<Number, Number> throttle = ChartUtil.lineChart("Throttle %", false);
    private final LineChart<Number, Number> brake = ChartUtil.lineChart("Brake %", false);
    private final LineChart<Number, Number> gear = ChartUtil.lineChart("Gear", true);
    private final ChartCursor cursor = new ChartCursor(this::onCursor);
    private final ScrollPane chartStack;

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

        VBox laps = new VBox(8, lapList, summary);
        VBox.setVgrow(lapList, Priority.ALWAYS);
        VBox lapsCard = Ui.card("Laps", laps);
        VBox left = new VBox(10, Ui.card("Sessions", sessionList), lapsCard);
        VBox.setVgrow(lapsCard, Priority.ALWAYS);
        left.setMinWidth(210);
        left.setPrefWidth(260);
        left.setMaxWidth(340);

        ChartUtil.fixedY(throttle, 0, 100, 25);
        ChartUtil.fixedY(brake, 0, 100, 25);
        speed.setMinHeight(120);
        speed.setPrefHeight(240);
        throttle.setPrefHeight(150);
        brake.setPrefHeight(150);
        gear.setMinHeight(104);
        gear.setPrefHeight(190);
        VBox charts = new VBox(8,
                cursor.attach(speed), cursor.attach(throttle), cursor.attach(brake), cursor.attach(gear));
        for (Node n : charts.getChildren()) {
            VBox.setVgrow(n, Priority.ALWAYS);
        }
        chartStack = ChartUtil.scrollable(charts);
        chartStack.setVisible(false);
        readout.visibleProperty().bind(chartStack.visibleProperty());
        VBox center = Ui.card("Channels", ChartUtil.withPlaceholder(chartStack, "Select a lap to plot its channels."),
                readout);
        center.setMinWidth(380);

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
        SessionLaps.theoreticalBest(s, 10).ifPresent(tb -> summaryRow(2, "Theoretical best",
                Formats.lapTime(tb.lapTime()), "Best of each of 10 sectors across the complete laps"));
        summary.setVisible(true);
        LapData initial = model.fastestLap(s).orElse(s.laps().isEmpty() ? null : s.laps().getFirst());
        if (initial != null) {
            lapList.getSelectionModel().select(initial);
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

    private void showLap(LapData lap) {
        SessionData s = sessionList.getSelectionModel().getSelectedItem();
        if (lap == null || s == null) {
            return;
        }
        current = model.analyze(s, lap);
        chartStack.setVisible(true);
        LapTrace t = current.trace();
        for (LineChart<Number, Number> c : List.of(speed, throttle, brake, gear)) {
            ChartUtil.xRange(c, t.lapLength());
        }
        ChartUtil.show(speed, ChartUtil.series("Speed", t.distance(), t.speed()));
        ChartUtil.show(throttle, ChartUtil.series("Throttle", t.distance(), t.throttle()));
        ChartUtil.show(brake, ChartUtil.series("Brake", t.distance(), t.brake()));
        ChartUtil.show(gear, ChartUtil.series("Gear", t.distance(), t.gear()));
        double topGear = java.util.Arrays.stream(t.gear()).max().orElse(6);
        ChartUtil.fixedY(gear, Math.min(0, java.util.Arrays.stream(t.gear()).min().orElse(0)), topGear + 1, 1);

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
        String time = Formats.lapTime(t.elapsed()[i]).replace("n/a", "0:00.000");
        // fixed-width fields keep the text still while the cursor moves; the compact form drops the time
        readout.show(String.format(Locale.ROOT, "%4.0f m · %s · %3.0f km/h · throttle %3.0f %% · brake %3.0f %% · gear %d",
                        t.distance()[i], time, t.speed()[i], t.throttle()[i], t.brake()[i], (int) t.gear()[i]),
                String.format(Locale.ROOT, "%4.0f m · %3.0f km/h · thr %3.0f%% · brk %3.0f%% · gear %d",
                        t.distance()[i], t.speed()[i], t.throttle()[i], t.brake()[i], (int) t.gear()[i]));
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
