package com.telemetry.desktop;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.dto.SegmentDeltaDto;
import com.telemetry.analyzer.service.AnalysisService;
import com.telemetry.analyzer.service.AnalyzerServices;
import com.telemetry.analyzer.service.TelemetryImportService;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TelemetryDesktopApp extends Application {

    private final AnalyzerServices services = new AnalyzerServices();

    private final ListView<SessionData> sessionList = new ListView<>();
    private final ComboBox<LapData> lapSelect = new ComboBox<>();
    private final Label lapTimeLabel = new Label("Lap time: -");
    private final Label summaryLabel = new Label("Import a telemetry file to begin.");
    private final TextArea reportArea = new TextArea();

    private final LineChart<Number, Number> speedChart = ChartUtil.lineChart("Distance / time", "km/h");
    private final LineChart<Number, Number> throttleChart = ChartUtil.lineChart("Distance / time", "%");
    private final LineChart<Number, Number> brakeChart = ChartUtil.lineChart("Distance / time", "%");
    private final LineChart<Number, Number> gearChart = ChartUtil.lineChart("Distance / time", "gear");
    private final LineChart<Number, Number> trackChart = ChartUtil.lineChart("Longitude", "Latitude");
    private final LineChart<Number, Number> compareChart = ChartUtil.lineChart("Distance / time", "km/h");

    private final ComboBox<SessionData> refSession = new ComboBox<>();
    private final ComboBox<SessionData> cmpSession = new ComboBox<>();
    private final ComboBox<LapData> refLap = new ComboBox<>();
    private final ComboBox<LapData> cmpLap = new ComboBox<>();
    private final Label compareSummary = new Label("Select two sessions and laps, then compare.");
    private final TableView<SegmentDeltaDto> segmentTable = new TableView<>();

    @Override
    public void start(Stage stage) {
        stage.setTitle("Motorsport Telemetry Analyzer");

        reportArea.setEditable(false);
        reportArea.setPrefRowCount(5);
        reportArea.getStyleClass().add("code-out");
        sessionList.setPrefWidth(280);
        sessionList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SessionData item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null
                        ? null
                        : item.sourceFile() + "  (" + item.laps().size() + " laps)");
            }
        });
        styleSessionCombo(refSession);
        styleSessionCombo(cmpSession);
        styleLapCombo(lapSelect);
        styleLapCombo(refLap);
        styleLapCombo(cmpLap);

        Button openBtn = new Button("Open file");
        openBtn.getStyleClass().add("btn-primary");
        openBtn.setOnAction(e -> openFile(stage));

        Button prevLap = new Button("Previous lap");
        Button nextLap = new Button("Next lap");
        prevLap.setOnAction(e -> shiftLap(-1));
        nextLap.setOnAction(e -> shiftLap(1));
        lapSelect.setOnAction(e -> renderCurrentLap());

        sessionList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null) {
                loadSession(n);
            }
        });

        HBox lapBar = new HBox(8, prevLap, lapSelect, nextLap, lapTimeLabel);
        lapBar.setAlignment(Pos.CENTER_LEFT);

        TabPane charts = new TabPane(
                tab("Speed", speedChart),
                tab("Throttle", throttleChart),
                tab("Brake", brakeChart),
                tab("Gear", gearChart),
                tab("Track map", trackChart)
        );
        charts.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        VBox left = new VBox(10,
                labeled("Sessions", sessionList),
                labeled("Current lap", lapBar),
                wrapSummary()
        );
        left.setPrefWidth(320);
        VBox.setVgrow(sessionList, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, charts);
        split.setDividerPositions(0.28);

        VBox compareBox = buildComparePane();
        TabPane mainTabs = new TabPane(
                tab("Lap charts", split),
                tab("Lap comparison", compareBox)
        );
        mainTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(mainTabs, Priority.ALWAYS);

        Label title = new Label("Telemetry Analyzer");
        title.getStyleClass().add("hero-title");
        Label tag = new Label("Local CSV · LDX · lap compare");
        tag.getStyleClass().add("hero-tag");
        HBox header = new HBox(16, title, tag, new Pane(), openBtn);
        HBox.setHgrow(header.getChildren().get(2), Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("hero");
        header.setPadding(new Insets(16));

        VBox root = new VBox(12, header, mainTabs, labeled("Import report", reportArea));
        root.setPadding(new Insets(0, 16, 16, 16));
        root.getStyleClass().add("root-pane");

        Scene scene = new Scene(root, 1280, 800);
        var css = getClass().getResource("/desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private VBox buildComparePane() {
        setupSegmentTable();
        Button run = new Button("Compare laps");
        run.getStyleClass().add("btn-primary");
        run.setOnAction(e -> runCompare());

        refSession.setOnAction(e -> fillLaps(refSession.getValue(), refLap));
        cmpSession.setOnAction(e -> fillLaps(cmpSession.getValue(), cmpLap));

        GridPane pickers = new GridPane();
        pickers.setHgap(10);
        pickers.setVgap(8);
        pickers.add(new Label("Reference session"), 0, 0);
        pickers.add(refSession, 1, 0);
        pickers.add(new Label("Reference lap"), 2, 0);
        pickers.add(refLap, 3, 0);
        pickers.add(new Label("Compare session"), 0, 1);
        pickers.add(cmpSession, 1, 1);
        pickers.add(new Label("Compare lap"), 2, 1);
        pickers.add(cmpLap, 3, 1);
        pickers.add(run, 4, 1);

        VBox box = new VBox(10, pickers, compareSummary, compareChart, segmentTable);
        VBox.setVgrow(compareChart, Priority.ALWAYS);
        VBox.setVgrow(segmentTable, Priority.SOMETIMES);
        box.setPadding(new Insets(12));
        return box;
    }

    private void setupSegmentTable() {
        TableColumn<SegmentDeltaDto, Number> idx = new TableColumn<>("#");
        idx.setCellValueFactory(c -> new javafx.beans.property.SimpleIntegerProperty(c.getValue().index()));
        TableColumn<SegmentDeltaDto, String> delta = new TableColumn<>("Δt (s)");
        delta.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.format("%+.3f", c.getValue().deltaSeconds())));
        TableColumn<SegmentDeltaDto, String> refSp = new TableColumn<>("Ref avg km/h");
        refSp.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.format("%.1f", c.getValue().referenceAvgSpeedKmh())));
        TableColumn<SegmentDeltaDto, String> cmpSp = new TableColumn<>("Cmp avg km/h");
        cmpSp.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.format("%.1f", c.getValue().compareAvgSpeedKmh())));
        TableColumn<SegmentDeltaDto, String> basis = new TableColumn<>("Basis");
        basis.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().distanceBased() ? "distance" : "index"));
        segmentTable.getColumns().setAll(List.of(idx, delta, refSp, cmpSp, basis));
        segmentTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        segmentTable.setPlaceholder(new Label("No segment deltas yet"));
        segmentTable.setPrefHeight(220);
    }

    private void openFile(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open telemetry file");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Telemetry files", "*.csv", "*.ld", "*.ldx"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            TelemetryImportService.ImportResult result = services.importService().importFile(bytes, file.getName());
            reportArea.setText(formatReport(result.report()));
            refreshSessions();
            if (result.session() != null) {
                sessionList.getSelectionModel().select(result.session());
            }
        } catch (Exception ex) {
            reportArea.setText("Failed to read file: " + ex.getMessage());
        }
    }

    private void refreshSessions() {
        List<SessionData> sessions = new ArrayList<>(services.sessionStore().list());
        sessions.sort(Comparator.comparing(SessionData::importedAt).reversed());
        sessionList.setItems(FXCollections.observableArrayList(sessions));
        refSession.setItems(FXCollections.observableArrayList(sessions));
        cmpSession.setItems(FXCollections.observableArrayList(sessions));
    }

    private void loadSession(SessionData session) {
        lapSelect.setItems(FXCollections.observableArrayList(session.laps()));
        if (!session.laps().isEmpty()) {
            lapSelect.getSelectionModel().selectFirst();
            renderCurrentLap();
        }
    }

    private void shiftLap(int delta) {
        int idx = lapSelect.getSelectionModel().getSelectedIndex();
        if (idx < 0) {
            return;
        }
        int next = Math.clamp(idx + delta, 0, lapSelect.getItems().size() - 1);
        lapSelect.getSelectionModel().select(next);
    }

    private void renderCurrentLap() {
        LapData lap = lapSelect.getValue();
        if (lap == null) {
            return;
        }
        lapTimeLabel.setText("Lap time: " + ChartUtil.formatLapTime(ChartUtil.lapDurationSeconds(lap)));
        AnalysisService.LapSummary summary = services.analysisService().summarizeLap(lap);
        summaryLabel.setText(String.format(
                "min %.1f km/h · avg %.1f km/h · max %.1f km/h%nthrottle %.1f%% · brake %.1f%% · gear changes %d · samples %d",
                summary.minSpeed(), summary.avgSpeed(), summary.maxSpeed(),
                summary.throttleUsagePercent(), summary.brakeUsagePercent(),
                summary.gearChanges(), lap.points().size()));

        replace(speedChart, ChartUtil.series("Speed", lap.points(), p -> p.speed()));
        replace(throttleChart, ChartUtil.series("Throttle", lap.points(), p -> p.throttle()));
        replace(brakeChart, ChartUtil.series("Brake", lap.points(), p -> p.brake()));
        replace(gearChart, ChartUtil.series("Gear", lap.points(), p -> p.gear()));
        trackChart.getData().clear();
        if (ChartUtil.hasGps(lap)) {
            trackChart.getData().add(ChartUtil.gpsSeries("Track", lap.points()));
        }
    }

    private void runCompare() {
        SessionData refS = refSession.getValue();
        SessionData cmpS = cmpSession.getValue();
        LapData rLap = refLap.getValue();
        LapData cLap = cmpLap.getValue();
        if (refS == null || cmpS == null || rLap == null || cLap == null) {
            compareSummary.setText("Pick both sessions and laps first.");
            return;
        }
        if (rLap.points().isEmpty() || cLap.points().isEmpty()) {
            compareSummary.setText("Cannot compare: one or both laps have no samples.");
            return;
        }
        AnalysisService.LapDelta delta = services.analysisService().compareLaps(rLap, cLap);
        compareSummary.setText(String.format(
                "Compared %d samples. Average speed delta (compare − reference): %+.2f km/h. Overlay: red = reference, cyan = compare.",
                delta.comparedSamples(), delta.avgSpeedDelta()));
        compareChart.getData().clear();
        var refSeries = ChartUtil.series("Reference", rLap.points(), p -> p.speed());
        var cmpSeries = ChartUtil.series("Compare", cLap.points(), p -> p.speed());
        compareChart.getData().addAll(refSeries, cmpSeries);
        List<SegmentDeltaDto> segs = services.segmentCompareService().compareLapSegments(rLap, cLap, 10);
        segmentTable.setItems(FXCollections.observableArrayList(segs));
    }

    private static void fillLaps(SessionData session, ComboBox<LapData> combo) {
        if (session == null) {
            combo.getItems().clear();
            return;
        }
        combo.setItems(FXCollections.observableArrayList(session.laps()));
        if (!session.laps().isEmpty()) {
            combo.getSelectionModel().selectFirst();
        }
    }

    @SafeVarargs
    private static void replace(LineChart<Number, Number> chart, javafx.scene.chart.XYChart.Series<Number, Number>... series) {
        chart.getData().clear();
        chart.getData().addAll(series);
    }

    private Label wrapSummary() {
        summaryLabel.setWrapText(true);
        summaryLabel.getStyleClass().add("summary");
        return summaryLabel;
    }

    private static Tab tab(String title, javafx.scene.Node content) {
        Tab t = new Tab(title, content);
        t.setClosable(false);
        return t;
    }

    private static VBox labeled(String title, javafx.scene.Node node) {
        Label l = new Label(title);
        l.getStyleClass().add("panel-title");
        return new VBox(6, l, node);
    }

    private static void styleSessionCombo(ComboBox<SessionData> combo) {
        combo.setPrefWidth(220);
        combo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SessionData item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.sourceFile());
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(SessionData item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.sourceFile());
            }
        });
    }

    private static void styleLapCombo(ComboBox<LapData> combo) {
        combo.setPrefWidth(140);
        combo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(LapData item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.lapId());
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(LapData item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.lapId());
            }
        });
    }

    private static String formatReport(ImportReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(report.getSourceFile()).append('\n');
        if (report.getErrors().isEmpty() && report.getWarnings().isEmpty()) {
            sb.append("Import OK.");
        }
        for (String e : report.getErrors()) {
            sb.append("ERROR: ").append(e).append('\n');
        }
        for (String w : report.getWarnings()) {
            sb.append("WARN: ").append(w).append('\n');
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
