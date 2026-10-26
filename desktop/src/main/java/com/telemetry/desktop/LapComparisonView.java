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
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private final LineChart<Number, Number> throttle = ChartUtil.lineChart("Throttle %", false);
    private final LineChart<Number, Number> brake = ChartUtil.lineChart("Brake %", true);
    private final ChartCursor cursor = new ChartCursor(this::onCursor);
    private final ChartUtil.Readout readout = new ChartUtil.Readout();
    private final Label refLegend = Ui.legend("", "dot-ref");
    private final Label cmpLegend = Ui.legend("", "dot-cmp");
    private final ScrollPane chartStack;

    private final TrackMapView map = new TrackMapView("Run a comparison to draw the track map.");
    private final TableView<SegmentDeltaDto> sectorTable = new TableView<>();
    private final TableView<BrakingPointDelta> brakingTable = new TableView<>();
    private final TabPane tables;
    private final Runnable fitTables;

    private final VBox root;
    private final VBox chartCard;
    private AlignedLapComparison comparison;

    LapComparisonView(SessionModel model) {
        this.model = model;
        for (ComboBox<SessionData> c : List.of(refSession, cmpSession)) {
            c.setItems(model.sessions());
            c.setCellFactory(l -> sessionCell());
            c.setButtonCell(sessionCell());
            c.setPrefWidth(230);
            c.setMinWidth(110);
        }
        for (ComboBox<LapData> c : List.of(refLap, cmpLap)) {
            c.setCellFactory(l -> new LapCell(model, true));
            c.setButtonCell(new LapCell(model, false));
            c.setPrefWidth(190);
            c.setMinWidth(150);
        }
        refSession.setOnAction(e -> fillLaps(refSession.getValue(), refLap, 0));
        cmpSession.setOnAction(e -> fillLaps(cmpSession.getValue(), cmpLap, cmpSession.getValue() == refSession.getValue() ? 1 : 0));

        Button run = new Button("Compare laps");
        run.getStyleClass().add("btn-primary");
        run.setOnAction(e -> compare());
        Button swap = new Button("Swap");
        swap.setOnAction(e -> swap());
        run.setMinWidth(Region.USE_PREF_SIZE);
        swap.setMinWidth(Region.USE_PREF_SIZE);

        HBox pickers = new HBox(14,
                picker("Reference", "dot-ref", refSession, refLap),
                picker("Compare", "dot-cmp", cmpSession, cmpLap),
                Ui.spacer(), swap, run);
        pickers.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().addAll("muted", "compare-status");
        status.setMinWidth(0);
        status.setTextOverrun(OverrunStyle.ELLIPSIS);
        Tooltip statusTip = new Tooltip();
        statusTip.textProperty().bind(status.textProperty());
        status.setTooltip(statusTip);
        VBox toolbar = new VBox(6, pickers, status);
        toolbar.getStyleClass().addAll("card", "toolbar");

        HBox tiles = new HBox(10,
                Ui.tile("Reference lap", refTime, "tile-ref"),
                Ui.tile("Compare lap", cmpTime, "tile-cmp"),
                Ui.tile("Δ lap time", deltaTime, "tile-delta"),
                Ui.tile("Biggest gain", bestSector, null),
                Ui.tile("Biggest loss", worstSector, null));
        tiles.setMinHeight(Region.USE_PREF_SIZE);

        ChartUtil.fixedY(throttle, 0, 100, 25);
        ChartUtil.fixedY(brake, 0, 100, 25);
        delta.setHorizontalZeroLineVisible(true);
        delta.getStyleClass().add("delta-chart");
        // minimums: the rotated axis labels still fit, and a window maximised on a 1280 x 720
        // screen (1280 x 680 work area) shows the whole stack without scrolling
        speed.setMinHeight(90);
        speed.setPrefHeight(230);
        delta.setMinHeight(56);
        delta.setPrefHeight(150);
        throttle.setMinHeight(70);
        throttle.setPrefHeight(130);
        brake.setMinHeight(90);
        brake.setPrefHeight(170);
        VBox charts = new VBox(8, cursor.attach(speed), cursor.attach(delta), cursor.attach(throttle),
                cursor.attach(brake));
        for (Node n : charts.getChildren()) {
            VBox.setVgrow(n, Priority.ALWAYS);
        }
        chartStack = ChartUtil.scrollable(charts);
        chartStack.setVisible(false);
        // short legends (the pickers above name the files) leave the header room for the readout
        for (Label l : List.of(readout, refLegend, cmpLegend)) {
            l.visibleProperty().bind(chartStack.visibleProperty());
        }
        for (Label l : List.of(refLegend, cmpLegend)) {
            l.setMinWidth(Region.USE_PREF_SIZE);
            l.setTooltip(new Tooltip());
        }
        chartCard = Ui.card("Traces", ChartUtil.withPlaceholder(chartStack, "Run a comparison to plot the traces."),
                readout, Ui.spacer(), refLegend, cmpLegend);
        chartCard.setMinWidth(380);

        setupTables();
        tables = Ui.cardTabs(Ui.tab("Sectors", sectorTable), Ui.tab("Braking points", brakingTable));
        SplitPane right = new SplitPane(Ui.card("Track map", map), tables);
        right.setOrientation(Orientation.VERTICAL);
        right.setDividerPositions(0.52);
        right.setMinWidth(380); // the six braking-point columns without sideways scrolling
        right.setPrefWidth(440);
        right.setMaxWidth(560);
        // the sector table gets the room for all its rows (or whole rows) below the map
        fitTables = Ui.fitBottom(right, 0.45, this::sectorsHeight);

        SplitPane center = new SplitPane(chartCard, right);
        center.setDividerPositions(0.67);
        root = new VBox(10, toolbar, tiles, center);
        VBox.setVgrow(center, Priority.ALWAYS);
        root.getStyleClass().add("view");
    }

    Node node() {
        return root;
    }

    /** Height of the tab card that shows every sector row, or whole rows within {@code max}. */
    private double sectorsHeight(double max) {
        double chrome = Ui.tabChrome(tables);
        Node header = sectorTable.lookup(".column-header-background");
        // the fixed row height comes from desktop.css, so it is only known once CSS is applied
        if (Double.isNaN(chrome) || !(header instanceof Region h) || sectorTable.getItems().isEmpty()
                || sectorTable.getFixedCellSize() <= 0) {
            return Double.NaN;
        }
        double head = chrome + sectorTable.snappedTopInset() + sectorTable.snappedBottomInset() + h.getHeight();
        return Ui.wholeRows(max, head, sectorTable.getFixedCellSize(), 0, sectorTable.getItems().size()) + 1;
    }

    Node trackMap() {
        return map;
    }

    Node charts() {
        return chartCard;
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

        refLegend.setText("Reference " + r.lapId());
        cmpLegend.setText("Compare " + c.lapId());
        refLegend.getTooltip().setText(refName);
        cmpLegend.getTooltip().setText(cmpName);
        chartStack.setVisible(true);
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
        fitTables.run();
        status.setText(String.format(Locale.ROOT, "Aligned on %s distance, %d points every %.0f m; compare lap length scaled %.4f×. "
                        + "Δt > 0: compare lap behind.",
                comparison.distanceFromChannel() ? "channel" : "integrated", comparison.size(),
                LapComparator.DEFAULT_STEP_METERS, comparison.referenceLapLength() / comparison.compareLapLength()));
    }

    private void onCursor(double distance) {
        cursor.show(distance);
        map.setCursorDistance(distance);
        if (comparison == null || Double.isNaN(distance)) {
            readout.show(null, null);
            return;
        }
        int i = Math.clamp(ChartUtil.indexAtOrAfter(comparison.distance(), distance), 0, comparison.size() - 1);
        double d = comparison.distance()[i];
        double dt = comparison.deltaTime()[i];
        double rs = comparison.referenceSpeed()[i];
        double cs = comparison.compareSpeed()[i];
        readout.show(String.format(Locale.ROOT, "%4.0f m · Δt %+.3f s · ref %3.0f km/h · cmp %3.0f km/h", d, dt, rs, cs),
                String.format(Locale.ROOT, "%4.0f m · Δt %+.3f s · %3.0f / %3.0f km/h", d, dt, rs, cs));
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
        TableColumn<SegmentDeltaDto, String> dt = Ui.col("Δt (s)", s -> Formats.signed(s.deltaSeconds(), 3));
        dt.setCellFactory(c -> signedCell());
        sectorTable.getColumns().setAll(List.of(
                Ui.keyCol("Sector", s -> "S" + s.index()),
                Ui.col("From–to (m)", s -> Formats.number(s.distanceStartM(), 0) + "–" + Formats.number(s.distanceEndM(), 0)),
                Ui.col("Ref avg (km/h)", s -> Formats.number(s.referenceAvgSpeedKmh(), 1)),
                Ui.col("Cmp avg (km/h)", s -> Formats.number(s.compareAvgSpeedKmh(), 1)),
                dt));
        Ui.fitColumns(sectorTable);
        sectorTable.setPlaceholder(Ui.muted("Run a comparison"));
        sectorTable.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> {
            if (s != null) {
                onCursor(s.distanceStartM());
            }
        });

        TableColumn<BrakingPointDelta, String> start = Ui.col("Δ start (m)", b -> Formats.signed(b.startDeltaMeters(), 1));
        start.setCellFactory(c -> signedCell());
        brakingTable.getColumns().setAll(List.of(
                Ui.keyCol("Zone", b -> "B" + b.referenceZone()),
                Ui.col("Ref start (m)", b -> Formats.number(b.referenceStart(), 0)),
                Ui.col("Cmp start (m)", b -> Formats.number(b.compareStart(), 0)),
                start,
                Ui.col("Δ min speed (km/h)", b -> Formats.signed(b.minSpeedDelta(), 1)),
                Ui.col("Δ time (s)", b -> Formats.signed(b.durationDelta(), 2))));
        Ui.fitColumns(brakingTable);
        brakingTable.setPlaceholder(Ui.muted("Run a comparison"));
        brakingTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                onCursor(b.referenceStart());
            }
        });
    }

    /** Colours "+x" red (compare slower / later) and "-x" green. */
    private static <T> TableCell<T, String> signedCell() {
        return new Ui.FitCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("loss", "gain");
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

    /** Label in the chart colour of that lap, followed by the session and lap pickers. */
    private static HBox picker(String label, String dot, ComboBox<SessionData> session, ComboBox<LapData> lap) {
        Label l = Ui.legend(label.toUpperCase(Locale.ROOT), dot);
        l.getStyleClass().add("field-label");
        l.setMinWidth(Region.USE_PREF_SIZE);
        HBox box = new HBox(6, l, session, lap);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(session, Priority.SOMETIMES);
        HBox.setHgrow(lap, Priority.SOMETIMES);
        return box;
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
}
