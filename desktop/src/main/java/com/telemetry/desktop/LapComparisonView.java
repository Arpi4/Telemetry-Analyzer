package com.telemetry.desktop;

import com.telemetry.analyzer.analysis.AlignedLapComparison;
import com.telemetry.analyzer.analysis.DrivingEvents.BrakingPointDelta;
import com.telemetry.analyzer.analysis.Interpolation;
import com.telemetry.analyzer.analysis.LapComparator;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.dto.SegmentDeltaDto;
import com.telemetry.desktop.SessionModel.LapAnalysis;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Two-lap comparison on a common distance axis: speed / pedal overlays, the cumulative time delta,
 * a track map coloured by where time is gained or lost, exact sector deltas and braking points.
 */
final class LapComparisonView {

    private static final int SECTORS = 10;
    /** Half window for the local time-loss rate shown on the map. */
    private static final double RATE_HALF_WINDOW_M = 25;

    private final SessionModel model;

    private final ComboBox<SessionData> refSession = new ComboBox<>();
    private final ComboBox<LapData> refLap = new ComboBox<>();
    private final ComboBox<SessionData> cmpSession = new ComboBox<>();
    private final ComboBox<LapData> cmpLap = new ComboBox<>();
    private final Label status = new Label("Pick a reference and a compare lap.");

    private final Label refTime = new Label("–");
    private final Label cmpTime = new Label("–");
    private final Label deltaTime = new Label("–");
    private final Label bestSector = new Label("–");
    private final Label worstSector = new Label("–");

    private final LineChart<Number, Number> speed = ChartUtil.lineChart("Speed (km/h)", false);
    private final LineChart<Number, Number> delta = ChartUtil.lineChart("Δt (s)", false);
    private final LineChart<Number, Number> throttle = ChartUtil.lineChart("Throttle (%)", false);
    private final LineChart<Number, Number> brake = ChartUtil.lineChart("Brake (%)", true);
    private final ChartCursor cursor = new ChartCursor(this::onCursor);
    private final Label readout = new Label(" ");

    private final TrackMapView map = new TrackMapView();
    private final TableView<SegmentDeltaDto> sectorTable = new TableView<>();
    private final TableView<BrakingPointDelta> brakingTable = new TableView<>();

    private final BorderPane root = new BorderPane();
    private final VBox chartBox;
    private AlignedLapComparison comparison;

    LapComparisonView(SessionModel model) {
        this.model = model;
        for (ComboBox<SessionData> c : List.of(refSession, cmpSession)) {
            c.setItems(model.sessions());
            c.setCellFactory(l -> sessionCell());
            c.setButtonCell(sessionCell());
            c.setPrefWidth(230);
        }
        for (ComboBox<LapData> c : List.of(refLap, cmpLap)) {
            c.setCellFactory(l -> lapCell());
            c.setButtonCell(lapCell());
            c.setPrefWidth(200);
        }
        refSession.setOnAction(e -> fillLaps(refSession.getValue(), refLap, 0));
        cmpSession.setOnAction(e -> fillLaps(cmpSession.getValue(), cmpLap, cmpSession.getValue() == refSession.getValue() ? 1 : 0));

        Button run = new Button("Compare laps");
        run.getStyleClass().add("btn-primary");
        run.setOnAction(e -> compare());
        Button swap = new Button("Swap");
        swap.setOnAction(e -> swap());

        GridPane pickers = new GridPane();
        pickers.setHgap(10);
        pickers.setVgap(6);
        pickers.addRow(0, muted("Reference"), refSession, refLap);
        pickers.addRow(1, muted("Compare"), cmpSession, cmpLap, run, swap);
        status.getStyleClass().add("muted");

        HBox tiles = new HBox(10,
                tile("Reference lap", refTime, "tile-ref"),
                tile("Compare lap", cmpTime, "tile-cmp"),
                tile("Δ lap time", deltaTime, null),
                tile("Biggest gain", bestSector, null),
                tile("Biggest loss", worstSector, null));
        VBox top = new VBox(8, pickers, tiles, status);
        top.setPadding(new Insets(10));

        ChartUtil.fixedY(throttle, 0, 100, 25);
        ChartUtil.fixedY(brake, 0, 100, 25);
        delta.setHorizontalZeroLineVisible(true);
        delta.getStyleClass().add("delta-chart");
        speed.setMinHeight(190);
        speed.setLegendVisible(true);
        chartBox = new VBox(2, cursor.attach(speed), cursor.attach(delta), cursor.attach(throttle),
                cursor.attach(brake), readout);
        for (Node n : chartBox.getChildren()) {
            VBox.setVgrow(n, Priority.ALWAYS);
        }
        VBox.setVgrow(readout, Priority.NEVER);
        readout.getStyleClass().add("readout");
        chartBox.setPadding(new Insets(6));

        setupTables();
        TabPane tables = new TabPane(new Tab("Sectors", sectorTable), new Tab("Braking points", brakingTable));
        tables.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        SplitPane right = new SplitPane(map, tables);
        right.setOrientation(Orientation.VERTICAL);
        right.setDividerPositions(0.55);

        SplitPane center = new SplitPane(chartBox, right);
        center.setDividerPositions(0.64);
        root.setTop(top);
        root.setCenter(center);
    }

    Node node() {
        return root;
    }

    Node trackMap() {
        return map;
    }

    Node charts() {
        return chartBox;
    }

    /**
     * Default pair: fastest lap of the first session against the fastest lap of the first other
     * session on a track of the same length, otherwise the second fastest lap of the same session.
     */
    void selectDefaults() {
        List<SessionData> sessions = model.sessions();
        if (sessions.isEmpty()) {
            return;
        }
        SessionData ref = sessions.getFirst();
        Optional<LapData> refFastest = model.fastestLap(ref);
        if (refFastest.isEmpty()) {
            return;
        }
        double refLength = model.analyze(ref, refFastest.get()).trace().lapLength();
        SessionData other = sessions.stream().skip(1)
                .filter(s -> model.fastestLap(s).map(l -> Math.abs(model.analyze(s, l).trace().lapLength() - refLength)
                        < 0.03 * refLength).orElse(false))
                .findFirst().orElse(ref);
        refSession.setValue(ref);
        fillLaps(ref, refLap, 0);
        cmpSession.setValue(other);
        fillLaps(other, cmpLap, other == ref ? 1 : 0);
    }

    void compare() {
        LapData r = refLap.getValue();
        LapData c = cmpLap.getValue();
        if (r == null || c == null) {
            status.setText("Pick both laps first.");
            return;
        }
        LapAnalysis ra = model.analyze(refSession.getValue(), r);
        LapAnalysis ca = model.analyze(cmpSession.getValue(), c);
        LapComparator comparator = model.services().lapComparator();
        try {
            comparison = comparator.compare(ra.trace(), ca.trace(), LapComparator.DEFAULT_STEP_METERS, true);
        } catch (IllegalArgumentException ex) {
            status.setText("Cannot compare: " + ex.getMessage());
            return;
        }
        List<SegmentDeltaDto> sectors = comparator.sectorDeltas(comparison, SECTORS);
        List<BrakingPointDelta> braking = model.services().drivingEventDetector()
                .compareBrakingPoints(ra.events(), ca.events());

        String refName = refSession.getValue().sourceFile() + " " + r.lapId();
        String cmpName = cmpSession.getValue().sourceFile() + " " + c.lapId();
        double[] d = comparison.distance();
        for (LineChart<Number, Number> chart : List.of(speed, delta, throttle, brake)) {
            ChartUtil.xRange(chart, d[d.length - 1]);
        }
        ChartUtil.show(speed, ChartUtil.series(refName, d, comparison.referenceSpeed()),
                ChartUtil.series(cmpName, d, comparison.compareSpeed()));
        ChartUtil.show(delta, ChartUtil.series("Δt", d, comparison.deltaTime()));
        ChartUtil.show(throttle, ChartUtil.series(refName, d, comparison.referenceThrottle()),
                ChartUtil.series(cmpName, d, comparison.compareThrottle()));
        ChartUtil.show(brake, ChartUtil.series(refName, d, comparison.referenceBrake()),
                ChartUtil.series(cmpName, d, comparison.compareBrake()));

        refTime.setText(Formats.lapTime(comparison.referenceLapTime()));
        cmpTime.setText(Formats.lapTime(comparison.compareLapTime()));
        deltaTime.setText(Formats.delta(comparison.lapTimeDelta()));
        setSign(deltaTime, comparison.lapTimeDelta());
        SegmentDeltaDto gain = sectors.stream().min((a, b) -> Double.compare(a.deltaSeconds(), b.deltaSeconds())).orElseThrow();
        SegmentDeltaDto loss = sectors.stream().max((a, b) -> Double.compare(a.deltaSeconds(), b.deltaSeconds())).orElseThrow();
        bestSector.setText("S" + gain.index() + "  " + Formats.delta(gain.deltaSeconds()));
        worstSector.setText("S" + loss.index() + "  " + Formats.delta(loss.deltaSeconds()));
        setSign(bestSector, gain.deltaSeconds());
        setSign(worstSector, loss.deltaSeconds());

        map.setTrack(ra.outline());
        if (!ra.outline().isEmpty()) {
            double[] rate = Interpolation.resample(d, lossRate(d, comparison.deltaTime()), ra.outline().distance());
            map.setValues(rate, TrackMapView.Scale.DELTA, "compare gains", "compare loses");
        }
        List<TrackMapView.Marker> markers = new ArrayList<>();
        for (SegmentDeltaDto s : sectors) {
            if (s.index() == 1) {
                continue; // starts at S/F, which is already marked
            }
            markers.add(new TrackMapView.Marker(s.distanceStartM(), "S" + s.index(),
                    s.deltaSeconds() > 0 ? Color.web("#ef4444") : Color.web("#22c55e")));
        }
        map.setMarkers(markers);

        sectorTable.setItems(FXCollections.observableArrayList(sectors));
        brakingTable.setItems(FXCollections.observableArrayList(braking));
        status.setText(String.format("Aligned on %s distance, %d points every %.0f m; compare lap length scaled %.4f×. "
                        + "Δt > 0: compare lap behind.",
                comparison.distanceFromChannel() ? "channel" : "integrated", comparison.size(),
                LapComparator.DEFAULT_STEP_METERS, comparison.referenceLapLength() / comparison.compareLapLength()));
    }

    private void onCursor(double distance) {
        cursor.show(distance);
        map.setCursorDistance(distance);
        if (comparison == null || Double.isNaN(distance)) {
            readout.setText(" ");
            return;
        }
        int i = Math.clamp(ChartUtil.indexAtOrAfter(comparison.distance(), distance), 0, comparison.size() - 1);
        readout.setText(String.format("%.0f m  ·  ref %.0f km/h  ·  cmp %.0f km/h  ·  Δt %+.3f s",
                comparison.distance()[i], comparison.referenceSpeed()[i], comparison.compareSpeed()[i],
                comparison.deltaTime()[i]));
    }

    /** d(Δt)/ds over a ±25 m window, in seconds per 100 m (positive = compare loses time). */
    static double[] lossRate(double[] distance, double[] deltaT) {
        int n = distance.length;
        double[] rate = new double[n];
        int lo = 0;
        int hi = 0;
        for (int k = 0; k < n; k++) {
            while (distance[lo] < distance[k] - RATE_HALF_WINDOW_M) {
                lo++;
            }
            while (hi < n - 1 && distance[hi + 1] <= distance[k] + RATE_HALF_WINDOW_M) {
                hi++;
            }
            double ds = distance[hi] - distance[lo];
            rate[k] = ds > 0 ? 100 * (deltaT[hi] - deltaT[lo]) / ds : 0;
        }
        return rate;
    }

    private void swap() {
        SessionData rs = refSession.getValue();
        LapData rl = refLap.getValue();
        refSession.setValue(cmpSession.getValue());
        fillLaps(refSession.getValue(), refLap, 0);
        refLap.setValue(cmpLap.getValue());
        cmpSession.setValue(rs);
        fillLaps(rs, cmpLap, 0);
        cmpLap.setValue(rl);
        compare();
    }

    /** Complete laps first (fastest first), then partial laps; preselects the n-th fastest. */
    private void fillLaps(SessionData s, ComboBox<LapData> combo, int preselect) {
        if (s == null) {
            combo.getItems().clear();
            return;
        }
        List<LapData> ordered = new ArrayList<>(model.lapsByTime(s));
        for (LapData l : s.laps()) {
            if (!ordered.contains(l)) {
                ordered.add(l);
            }
        }
        combo.setItems(FXCollections.observableArrayList(ordered));
        if (!ordered.isEmpty()) {
            combo.setValue(ordered.get(Math.min(preselect, ordered.size() - 1)));
        }
    }

    private void setupTables() {
        TableColumn<SegmentDeltaDto, String> dt = LapAnalysisView.col("Δt (s)", s -> Formats.signed(s.deltaSeconds(), 3));
        dt.setCellFactory(c -> signedCell());
        sectorTable.getColumns().setAll(List.of(
                LapAnalysisView.col("Sector", s -> "S" + s.index()),
                LapAnalysisView.col("From–to (m)", s -> Formats.number(s.distanceStartM(), 0) + "–" + Formats.number(s.distanceEndM(), 0)),
                LapAnalysisView.col("Ref avg (km/h)", s -> Formats.number(s.referenceAvgSpeedKmh(), 1)),
                LapAnalysisView.col("Cmp avg (km/h)", s -> Formats.number(s.compareAvgSpeedKmh(), 1)),
                dt));
        sectorTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        sectorTable.setPlaceholder(new Label("Run a comparison"));
        sectorTable.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> {
            if (s != null) {
                onCursor(s.distanceStartM());
            }
        });

        TableColumn<BrakingPointDelta, String> start = LapAnalysisView.col("Δ brake point (m)", b -> Formats.signed(b.startDeltaMeters(), 1));
        start.setCellFactory(c -> signedCell());
        brakingTable.getColumns().setAll(List.of(
                LapAnalysisView.col("Zone", b -> "B" + b.referenceZone()),
                LapAnalysisView.col("Ref start (m)", b -> Formats.number(b.referenceStart(), 0)),
                LapAnalysisView.col("Cmp start (m)", b -> Formats.number(b.compareStart(), 0)),
                start,
                LapAnalysisView.col("Δ min speed (km/h)", b -> Formats.signed(b.minSpeedDelta(), 1)),
                LapAnalysisView.col("Δ duration (s)", b -> Formats.signed(b.durationDelta(), 2))));
        brakingTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        brakingTable.setPlaceholder(new Label("Run a comparison"));
        brakingTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                onCursor(b.referenceStart());
            }
        });
    }

    /** Colours "+x" red (compare slower / later) and "-x" green. */
    private static <T> TableCell<T, String> signedCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("loss", "gain");
                setText(empty ? null : item);
                if (!empty && item != null) {
                    getStyleClass().add(item.startsWith("+") ? "loss" : "gain");
                }
            }
        };
    }

    private static void setSign(Label l, double value) {
        l.getStyleClass().removeAll("loss", "gain");
        l.getStyleClass().add(value > 0 ? "loss" : "gain");
    }

    private static VBox tile(String title, Label value, String accent) {
        Label t = new Label(title);
        t.getStyleClass().add("tile-title");
        value.getStyleClass().add("tile-value");
        VBox box = new VBox(2, t, value);
        box.getStyleClass().add("tile");
        if (accent != null) {
            box.getStyleClass().add(accent);
        }
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        return l;
    }

    private static ListCell<SessionData> sessionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(SessionData s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.sourceFile());
            }
        };
    }

    private ListCell<LapData> lapCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(LapData lap, boolean empty) {
                super.updateItem(lap, empty);
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
                setText(lap.lapId() + "  " + Formats.lapTime(a.trace().lapTime())
                        + (a.fastest() ? "  ★" : a.complete() ? "" : "  (partial)"));
            }
        };
    }
}
