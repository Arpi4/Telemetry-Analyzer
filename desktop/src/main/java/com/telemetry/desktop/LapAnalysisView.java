package com.telemetry.desktop;

import com.telemetry.analyzer.analysis.DrivingEvents.BrakingZone;
import com.telemetry.analyzer.analysis.DrivingEvents.CornerApex;
import com.telemetry.analyzer.analysis.DrivingEvents.DrivingStyleMetrics;
import com.telemetry.analyzer.analysis.LapTrace;
import com.telemetry.analyzer.analysis.SessionLaps;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.desktop.SessionModel.LapAnalysis;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Single-lap view: session and lap pickers, channel charts over lap distance, track map coloured
 * by speed, and the detected driving events.
 */
final class LapAnalysisView {

    private final SessionModel model;

    private final ListView<SessionData> sessionList = new ListView<>();
    private final ListView<LapData> lapList = new ListView<>();
    private final Label sessionSummary = new Label("Open a telemetry file to begin.");
    private final Label readout = new Label(" ");

    private final LineChart<Number, Number> speed = ChartUtil.lineChart("Speed (km/h)", false);
    private final LineChart<Number, Number> throttle = ChartUtil.lineChart("Throttle (%)", false);
    private final LineChart<Number, Number> brake = ChartUtil.lineChart("Brake (%)", false);
    private final LineChart<Number, Number> gear = ChartUtil.lineChart("Gear", true);
    private final ChartCursor cursor = new ChartCursor(this::onCursor);

    private final TrackMapView map = new TrackMapView();
    private final GridPane metrics = new GridPane();
    private final TableView<BrakingZone> brakingTable = new TableView<>();
    private final TableView<CornerApex> cornerTable = new TableView<>();

    private final SplitPane root;
    private LapAnalysis current;

    LapAnalysisView(SessionModel model) {
        this.model = model;

        sessionList.setItems(model.sessions());
        sessionList.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(SessionData s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.sourceFile() + "  ·  " + s.laps().size() + " laps");
            }
        });
        sessionList.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> showSession(s));
        sessionList.setPrefHeight(150);

        lapList.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(LapData lap, boolean empty) {
                super.updateItem(lap, empty);
                getStyleClass().removeAll("lap-fastest", "lap-incomplete");
                if (empty || lap == null) {
                    setText(null);
                    return;
                }
                SessionData owner = model.sessionOf(lap);
                if (owner == null) {
                    setText(lap.lapId());
                    return;
                }
                LapAnalysis a = model.analyze(owner, lap);
                String flag = a.fastest() ? "  ★ fastest" : a.complete() ? "" : "  (partial)";
                setText(String.format("%-7s %10s%s", lap.lapId(), Formats.lapTime(a.trace().lapTime()), flag));
                getStyleClass().add(a.fastest() ? "lap-fastest" : a.complete() ? "" : "lap-incomplete");
            }
        });
        lapList.getSelectionModel().selectedItemProperty().addListener((o, a, lap) -> showLap(lap));
        lapList.getStyleClass().add("mono");

        sessionSummary.setWrapText(true);
        sessionSummary.setMinHeight(Region.USE_PREF_SIZE);
        sessionSummary.getStyleClass().add("summary");

        VBox left = new VBox(8,
                title("Sessions"), sessionList,
                title("Laps"), lapList,
                sessionSummary);
        VBox.setVgrow(lapList, Priority.ALWAYS);
        left.setPadding(new Insets(10));
        left.setMinWidth(240);

        ChartUtil.fixedY(throttle, 0, 100, 25);
        ChartUtil.fixedY(brake, 0, 100, 25);
        speed.setMinHeight(200);
        VBox charts = new VBox(2,
                cursor.attach(speed), cursor.attach(throttle), cursor.attach(brake), cursor.attach(gear), readout);
        for (Node n : charts.getChildren()) {
            VBox.setVgrow(n, Priority.ALWAYS);
        }
        VBox.setVgrow(readout, Priority.NEVER);
        readout.getStyleClass().add("readout");
        charts.setPadding(new Insets(6));

        setupTables();
        metrics.setHgap(14);
        metrics.setVgap(6);
        metrics.setPadding(new Insets(10));
        TabPane details = new TabPane(
                new Tab("Metrics", metrics),
                new Tab("Braking zones", brakingTable),
                new Tab("Corners", cornerTable));
        details.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        SplitPane right = new SplitPane(map, details);
        right.setOrientation(Orientation.VERTICAL);
        right.setDividerPositions(0.55);
        right.setMinWidth(320);

        root = new SplitPane(left, charts, right);
        root.setDividerPositions(0.2, 0.68);
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
            return;
        }
        lapList.setItems(FXCollections.observableArrayList(s.laps()));
        List<LapData> complete = model.completeLaps(s);
        StringBuilder sb = new StringBuilder();
        sb.append(complete.size()).append(" complete of ").append(s.laps().size()).append(" laps");
        model.fastestLap(s).ifPresent(f -> sb.append("\nFastest: ").append(f.lapId()).append("  ")
                .append(Formats.lapTime(model.analyze(s, f).trace().lapTime())));
        SessionLaps.theoreticalBest(s, 10).ifPresent(tb -> sb.append("\nTheoretical best (10 sectors): ")
                .append(Formats.lapTime(tb.lapTime())));
        sessionSummary.setText(sb.toString());
        LapData initial = model.fastestLap(s).orElse(s.laps().isEmpty() ? null : s.laps().getFirst());
        if (initial != null) {
            lapList.getSelectionModel().select(initial);
        }
    }

    private void showLap(LapData lap) {
        SessionData s = sessionList.getSelectionModel().getSelectedItem();
        if (lap == null || s == null) {
            return;
        }
        current = model.analyze(s, lap);
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
            readout.setText(" ");
            return;
        }
        LapTrace t = current.trace();
        int i = Math.clamp(ChartUtil.indexAtOrAfter(t.distance(), distance), 0, t.size() - 1);
        readout.setText(String.format("%.0f m  ·  %s  ·  %.0f km/h  ·  throttle %.0f %%  ·  brake %.0f %%  ·  gear %d",
                t.distance()[i], Formats.lapTime(t.elapsed()[i]).replace("n/a", "0:00.000"), t.speed()[i],
                t.throttle()[i], t.brake()[i], (int) t.gear()[i]));
    }

    private void fillMetrics(DrivingStyleMetrics m, int zones, int corners) {
        metrics.getChildren().clear();
        String[][] rows = {
                {"Lap time", Formats.lapTime(m.lapTime())},
                {"Lap length", Formats.number(m.lapLength(), 0) + " m"},
                {"Average speed", Formats.number(m.averageSpeed(), 1) + " km/h"},
                {"Top speed", Formats.number(m.maxSpeed(), 1) + " km/h"},
                {"Full throttle", Formats.number(m.fullThrottlePercent(), 1) + " % of lap time"},
                {"Braking", Formats.number(m.brakingPercent(), 1) + " %"},
                {"Coasting", Formats.number(m.coastingPercent(), 1) + " %"},
                {"Throttle + brake overlap", Formats.number(m.pedalOverlapPercent(), 1) + " %"},
                {"Gear changes", String.valueOf(m.gearChanges())},
                {"Braking zones / corners", zones + " / " + corners},
        };
        for (int r = 0; r < rows.length; r++) {
            Label k = new Label(rows[r][0]);
            k.getStyleClass().add("muted");
            Label v = new Label(rows[r][1]);
            v.getStyleClass().add("metric-value");
            metrics.addRow(r, k, v);
        }
    }

    private void setupTables() {
        brakingTable.getColumns().setAll(List.of(
                col("#", z -> String.valueOf(z.index())),
                col("Start (m)", z -> Formats.number(z.startDistance(), 0)),
                col("Length (m)", z -> Formats.number(z.endDistance() - z.startDistance(), 0)),
                col("Entry (km/h)", z -> Formats.number(z.entrySpeed(), 0)),
                col("Min (km/h)", z -> Formats.number(z.minSpeed(), 0)),
                col("Time (s)", z -> Formats.number(z.duration(), 2)),
                col("Peak (%)", z -> Formats.number(z.peakBrake(), 0))));
        brakingTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        brakingTable.setPlaceholder(new Label("No braking zones"));
        brakingTable.getSelectionModel().selectedItemProperty().addListener((o, a, z) -> {
            if (z != null) {
                onCursor(z.startDistance());
            }
        });

        cornerTable.getColumns().setAll(List.of(
                col("#", (CornerApex c) -> String.valueOf(c.index())),
                col("Apex (m)", c -> Formats.number(c.distance(), 0)),
                col("Min (km/h)", c -> Formats.number(c.minSpeed(), 0)),
                col("Entry (km/h)", c -> Formats.number(c.entrySpeed(), 0)),
                col("Exit (km/h)", c -> Formats.number(c.exitSpeed(), 0)),
                col("Gear", c -> String.valueOf(c.gear()))));
        cornerTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        cornerTable.setPlaceholder(new Label("No corners detected"));
        cornerTable.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> {
            if (c != null) {
                onCursor(c.distance());
            }
        });
    }

    static <T> TableColumn<T, String> col(String name, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cell -> new SimpleObjectProperty<>(value.apply(cell.getValue())));
        c.setSortable(false);
        return c;
    }

    /** Channel values evaluated at the given distances (outline samples). */
    static double[] at(double[] targetDistance, double[] sourceDistance, double[] values) {
        return com.telemetry.analyzer.analysis.Interpolation.resample(sourceDistance, values, targetDistance);
    }

    static Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("panel-title");
        return l;
    }
}
