package com.telemetry.desktop;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.service.TelemetryImportService.ImportResult;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline desktop client. Parsing and analysis run in-process through the shared core module;
 * there is no HTTP and no server dependency.
 *
 * <p>Command line: telemetry files to open, plus {@code --screenshots=<dir>} to render the main
 * views to PNG files and exit (used for documentation screenshots).
 */
public class TelemetryDesktopApp extends Application {

    private final SessionModel model = new SessionModel();
    private final LapAnalysisView lapView = new LapAnalysisView(model);
    private final LapComparisonView compareView = new LapComparisonView(model);
    private final TextArea reportArea = new TextArea();
    private final Label status = new Label("Ready. Open or drop CSV / LDX telemetry files.");
    private final ProgressBar progress = new ProgressBar();
    private final TabPane tabs = new TabPane();
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("Motorsport Telemetry Analyzer");

        Label title = new Label("Telemetry Analyzer");
        title.getStyleClass().add("hero-title");
        Label tag = new Label("Offline · MoTeC CSV · generic CSV · LDX · lap compare");
        tag.getStyleClass().add("hero-tag");
        Button open = new Button("Open files…");
        open.getStyleClass().add("btn-primary");
        open.setOnAction(e -> chooseFiles());
        Button export = new Button("Export view as PNG");
        export.setOnAction(e -> exportCurrentView());
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(16, title, tag, spacer, export, open);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("hero");
        header.setPadding(new Insets(12, 16, 12, 16));

        reportArea.setEditable(false);
        reportArea.getStyleClass().add("code-out");
        tabs.getTabs().addAll(
                new Tab("Lap analysis", lapView.node()),
                new Tab("Lap comparison", compareView.node()),
                new Tab("Import report", reportArea));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        progress.setVisible(false);
        progress.setPrefWidth(160);
        HBox statusBar = new HBox(10, progress, status);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setPadding(new Insets(6, 12, 6, 12));

        BorderPane root = new BorderPane(tabs, header, null, statusBar, null);
        root.getStyleClass().add("root-pane");

        Scene scene = new Scene(root, 1600, 980);
        var css = getClass().getResource("/desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            boolean ok = e.getDragboard().hasFiles();
            if (ok) {
                importFiles(e.getDragboard().getFiles().stream().map(File::toPath).toList(), null);
            }
            e.setDropCompleted(ok);
            e.consume();
        });
        stage.setScene(scene);
        stage.show();

        List<Path> files = new ArrayList<>();
        Path screenshots = null;
        for (String arg : getParameters().getRaw()) {
            if (arg.startsWith("--screenshots=")) {
                screenshots = Path.of(arg.substring("--screenshots=".length()));
            } else if (!arg.startsWith("--")) {
                files.add(Path.of(arg));
            }
        }
        if (screenshots != null) {
            // figures for print: a smaller window with a larger font keeps the UI text legible
            // when the screenshot is scaled down to the page width
            root.setStyle("-fx-font-size: 14px;");
            stage.setWidth(1280);
            stage.setHeight(860);
        }
        if (!files.isEmpty()) {
            Path shotDir = screenshots;
            importFiles(files, shotDir == null ? null : () -> renderScreenshots(shotDir));
        }
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open telemetry files");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Telemetry files", "*.csv", "*.ld", "*.ldx"));
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            importFiles(files.stream().map(File::toPath).toList(), null);
        }
    }

    /** Parses on a worker thread; the UI stays responsive during large MoTeC imports. */
    private void importFiles(List<Path> files, Runnable afterImport) {
        Task<List<ImportResult>> task = new Task<>() {
            @Override
            protected List<ImportResult> call() throws IOException {
                List<ImportResult> results = new ArrayList<>();
                for (int i = 0; i < files.size(); i++) {
                    updateMessage("Importing " + files.get(i).getFileName() + " (" + (i + 1) + "/" + files.size() + ")…");
                    updateProgress(i, files.size());
                    results.add(model.importFile(files.get(i)));
                }
                updateProgress(files.size(), files.size());
                return results;
            }
        };
        progress.progressProperty().bind(task.progressProperty());
        status.textProperty().bind(task.messageProperty());
        progress.setVisible(true);
        long started = System.nanoTime();
        task.setOnSucceeded(e -> {
            unbindStatus();
            List<ImportResult> results = task.getValue();
            int ok = 0;
            SessionData last = null;
            for (ImportResult r : results) {
                appendReport(r.report());
                if (r.session() != null) {
                    ok++;
                    last = r.session();
                }
            }
            model.refresh();
            status.setText(String.format(Locale.ROOT, "Imported %d of %d file(s) in %.1f s.", ok, results.size(),
                    (System.nanoTime() - started) / 1e9));
            if (last != null) {
                lapView.select(last);
                compareView.selectDefaults();
            } else {
                tabs.getSelectionModel().select(2);
            }
            if (afterImport != null) {
                afterImport.run();
            }
        });
        task.setOnFailed(e -> {
            unbindStatus();
            Throwable ex = task.getException();
            status.setText("Import failed: " + (ex == null ? "unknown error" : ex.getMessage()));
            reportArea.appendText(timestamp() + " Import failed: " + ex + "\n");
            tabs.getSelectionModel().select(2);
        });
        Thread worker = new Thread(task, "telemetry-import");
        worker.setDaemon(true);
        worker.start();
    }

    private void unbindStatus() {
        status.textProperty().unbind();
        progress.progressProperty().unbind();
        progress.setVisible(false);
    }

    private void appendReport(ImportReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append(timestamp()).append(' ').append(report.getSourceFile()).append(": ")
                .append(report.getErrors().isEmpty() ? "OK" : "FAILED").append('\n');
        for (String err : report.getErrors()) {
            sb.append("    ERROR ").append(err).append('\n');
        }
        for (String w : report.getWarnings()) {
            sb.append("    note  ").append(w).append('\n');
        }
        reportArea.appendText(sb.toString());
    }

    private void exportCurrentView() {
        Node node = tabs.getSelectionModel().getSelectedItem().getContent();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export view as PNG");
        chooser.setInitialFileName("telemetry-view.png");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            ImageExport.savePng(node, file.toPath());
            status.setText("Saved " + file.getName());
        } catch (IOException ex) {
            status.setText("Export failed: " + ex.getMessage());
        }
    }

    /** Renders the main views to PNG files, then exits. */
    private void renderScreenshots(Path dir) {
        List<Runnable> steps = new ArrayList<>();
        steps.add(() -> {
            // same lap as the comparison reference, so the figures belong together
            lapView.select(model.sessions().getFirst());
            tabs.getSelectionModel().select(0);
        });
        steps.add(() -> {
            save(tabs.getSelectionModel().getSelectedItem().getContent(), dir.resolve("desktop-lap-analysis.png"));
            save(lapView.trackMap(), dir.resolve("desktop-track-map-speed.png"));
            tabs.getSelectionModel().select(1);
            compareView.compare();
        });
        steps.add(() -> {
            save(compareView.node(), dir.resolve("desktop-lap-comparison.png"));
            save(compareView.charts(), dir.resolve("desktop-compare-charts.png"));
            save(compareView.trackMap(), dir.resolve("desktop-track-map-delta.png"));
            tabs.getSelectionModel().select(2);
        });
        steps.add(() -> {
            save(reportArea, dir.resolve("desktop-import-report.png"));
            Platform.exit();
        });
        runSteps(steps, 0);
    }

    private void runSteps(List<Runnable> steps, int i) {
        if (i >= steps.size()) {
            return;
        }
        PauseTransition pause = new PauseTransition(Duration.millis(900));
        pause.setOnFinished(e -> {
            steps.get(i).run();
            runSteps(steps, i + 1);
        });
        pause.play();
    }

    private void save(Node node, Path file) {
        try {
            ImageExport.savePng(node, file);
            System.out.println("saved " + file.toAbsolutePath());
        } catch (IOException ex) {
            System.err.println("could not save " + file + ": " + ex.getMessage());
        }
    }

    private static String timestamp() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
