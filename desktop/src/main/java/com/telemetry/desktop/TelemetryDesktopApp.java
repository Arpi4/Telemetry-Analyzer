package com.telemetry.desktop;

import com.telemetry.analyzer.domain.ImportReport;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.service.TelemetryImportService.ImportResult;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Dimension2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
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
 * views to PNG files and exit (used for documentation screenshots), and {@code --size=WIDTHxHEIGHT}
 * to open the window at a fixed size instead of fitting it to the screen. With {@code --size} the
 * screenshots keep the normal UI font, so the layout can be checked at a given screen size.
 */
public class TelemetryDesktopApp extends Application {

    /** Window size on large screens; smaller screens get a maximised window instead. */
    private static final double PREF_WIDTH = 1600;
    private static final double PREF_HEIGHT = 980;
    /**
     * Below this the three-column views no longer fit; still smaller than the work area of a
     * 1280x720 laptop screen (1280x680 without the taskbar).
     */
    private static final double MIN_WIDTH = 1180;
    private static final double MIN_HEIGHT = 680;
    private static final KeyCode[] VIEW_KEYS = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3};
    /** Import log line length; about 160 Consolas characters are visible at the minimum window width. */
    private static final int REPORT_COLUMNS = 132;

    private final SessionModel model = new SessionModel();
    private final LapAnalysisView lapView = new LapAnalysisView(model);
    private final LapComparisonView compareView = new LapComparisonView(model);
    private final TextArea reportArea = new TextArea();
    private final Label status = new Label("Ready. Open or drop CSV / LDX telemetry files.");
    private final ProgressBar progress = new ProgressBar();
    private final ToggleGroup nav = new ToggleGroup();
    private final List<Node> views = new ArrayList<>();
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("Motorsport Telemetry Analyzer");
        for (int size : new int[] {16, 24, 32, 48, 64, 256}) {
            stage.getIcons().add(icon(size));
        }

        List<Path> files = new ArrayList<>();
        Path screenshots = null;
        Dimension2D size = null;
        for (String arg : getParameters().getRaw()) {
            if (arg.startsWith("--screenshots=")) {
                screenshots = Path.of(arg.substring("--screenshots=".length()));
            } else if (arg.startsWith("--size=")) {
                size = parseSize(arg.substring("--size=".length()));
            } else if (!arg.startsWith("--")) {
                files.add(Path.of(arg));
            }
        }

        reportArea.setEditable(false);
        reportArea.getStyleClass().add("code-out");
        StackPane reportView = new StackPane(Ui.card("Import log", reportArea));
        reportView.getStyleClass().add("view");
        views.addAll(List.of(lapView.node(), compareView.node(), reportView));
        StackPane content = new StackPane(views.toArray(Node[]::new));

        // display text follows the platform: Ctrl+O on Windows and Linux, ⌘O on macOS
        KeyCombination openKey = new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN);
        Button open = new Button("Open files…");
        open.getStyleClass().add("btn-primary");
        open.setTooltip(new Tooltip("Open CSV / LD / LDX telemetry files (" + openKey.getDisplayText() + ")"));
        open.setOnAction(e -> chooseFiles());
        Button export = new Button("Export PNG");
        export.setTooltip(new Tooltip("Save the current view as a PNG image"));
        export.setOnAction(e -> exportCurrentView());
        for (Button b : List.of(export, open)) {
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox actions = new HBox(8, export, open);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        HBox navBar = new HBox(navButton("Lap analysis"), navButton("Lap comparison"), navButton("Import report"));
        navBar.getStyleClass().add("nav");
        nav.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) {
                nav.selectToggle(old); // one view is always shown
                return;
            }
            int index = nav.getToggles().indexOf(now);
            for (int i = 0; i < views.size(); i++) {
                views.get(i).setVisible(i == index);
            }
        });
        showView(0);
        HBox header = new HBox(20, brand(), navBar, Ui.spacer(), actions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-bar");

        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        progress.setPrefWidth(160);
        status.setMinWidth(0);
        Label hint = new Label("Drop telemetry files anywhere  ·  " + openKey.getDisplayText() + " to open");
        hint.setMinWidth(Region.USE_PREF_SIZE);
        HBox statusBar = new HBox(10, progress, status, Ui.spacer(), hint);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");

        BorderPane root = new BorderPane(content, header, null, statusBar, null);
        root.getStyleClass().add("root-pane");

        Scene scene = new Scene(root);
        var css = getClass().getResource("/desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        scene.setFill(Color.web("#07090d"));
        scene.getAccelerators().put(openKey, this::chooseFiles);
        for (int i = 0; i < views.size(); i++) {
            int view = i;
            scene.getAccelerators().put(viewKey(i), () -> showView(view));
        }
        // Ctrl+Tab / Ctrl+Shift+Tab cycle the views, as when the views were tabs
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN),
                () -> showView((currentIndex() + 1) % views.size()));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN,
                KeyCombination.SHIFT_DOWN), () -> showView((currentIndex() + views.size() - 1) % views.size()));
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

        if (size != null) {
            stage.setWidth(size.getWidth());
            stage.setHeight(size.getHeight());
        } else if (screenshots != null) {
            // figures for print: a smaller window with a larger font keeps the UI text legible
            // when the screenshot is scaled down to the page width
            root.setStyle("-fx-font-size: 14px;");
            stage.setWidth(1280);
            stage.setHeight(860);
        } else {
            fitToScreen(stage);
        }
        stage.show();

        if (!files.isEmpty()) {
            Path shotDir = screenshots;
            importFiles(files, shotDir == null ? null : () -> renderScreenshots(shotDir));
        }
    }

    /**
     * Never larger than the work area of the primary screen: large screens get a centred window
     * of the preferred size, smaller ones (laptops) a maximised window.
     */
    private static void fitToScreen(Stage stage) {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        boolean small = screen.getWidth() < PREF_WIDTH || screen.getHeight() < PREF_HEIGHT;
        // size restored when a maximised window is un-maximised
        double w = small ? Math.floor(screen.getWidth() * 0.9) : PREF_WIDTH;
        double h = small ? Math.floor(screen.getHeight() * 0.9) : PREF_HEIGHT;
        stage.setMinWidth(Math.min(MIN_WIDTH, screen.getWidth()));
        stage.setMinHeight(Math.min(MIN_HEIGHT, screen.getHeight()));
        stage.setWidth(w);
        stage.setHeight(h);
        stage.setX(screen.getMinX() + (screen.getWidth() - w) / 2);
        stage.setY(screen.getMinY() + (screen.getHeight() - h) / 2);
        stage.setMaximized(small);
    }

    private static Dimension2D parseSize(String value) {
        String[] parts = value.toLowerCase(Locale.ROOT).split("x");
        try {
            if (parts.length == 2) {
                return new Dimension2D(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new IllegalArgumentException("--size expects WIDTHxHEIGHT, e.g. --size=1536x912, got: " + value);
    }

    /** Logo, product name, offline badge and the supported formats, as the web client's header. */
    private static HBox brand() {
        Label title = new Label("TELEMETRY ANALYZER");
        title.getStyleClass().add("brand-title");
        Label badge = new Label("OFFLINE");
        badge.getStyleClass().add("brand-badge");
        HBox name = new HBox(8, title, badge);
        name.setAlignment(Pos.CENTER_LEFT);
        Label tag = new Label("MoTeC CSV · generic CSV · LDX · lap compare");
        tag.getStyleClass().add("brand-tag");
        VBox text = new VBox(0, name, tag);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox brand = new HBox(12, logo(34), text);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setMinWidth(Region.USE_PREF_SIZE);
        return brand;
    }

    /** Brand mark: a speed trace (straight, braking, exit) on a red tile. Also the window icon. */
    private static Canvas logo(double size) {
        Canvas canvas = new Canvas(size, size);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#ff4a3a")), new Stop(1, Color.web("#c42a1f"))));
        g.fillRoundRect(0, 0, size, size, size * 0.42, size * 0.42);
        g.setStroke(Color.WHITE);
        g.setLineWidth(Math.max(1.6, size * 0.1));
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        double[] xs = {0.18, 0.44, 0.56, 0.82};
        double[] ys = {0.70, 0.28, 0.70, 0.36};
        for (int i = 0; i < xs.length; i++) {
            xs[i] *= size;
            ys[i] *= size;
        }
        g.strokePolyline(xs, ys, xs.length);
        return canvas;
    }

    private static Image icon(int size) {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return logo(size).snapshot(params, null);
    }

    /** Ctrl+1, Ctrl+2, … (⌘ on macOS) select the views in nav order. */
    private static KeyCombination viewKey(int index) {
        return new KeyCodeCombination(VIEW_KEYS[index], KeyCombination.SHORTCUT_DOWN);
    }

    private ToggleButton navButton(String text) {
        ToggleButton b = new ToggleButton(text.toUpperCase(Locale.ROOT));
        b.setTooltip(new Tooltip(text + " (" + viewKey(nav.getToggles().size()).getDisplayText() + ")"));
        b.setToggleGroup(nav);
        b.getStyleClass().add("nav-tab");
        b.setMaxHeight(Double.MAX_VALUE);
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    private void showView(int index) {
        nav.selectToggle(nav.getToggles().get(index));
    }

    private int currentIndex() {
        return nav.getToggles().indexOf(nav.getSelectedToggle());
    }

    private Node currentView() {
        return views.get(currentIndex());
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
                showView(2);
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
            showView(2);
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
            appendWrapped(sb, "    ERROR ", err);
        }
        for (String w : report.getWarnings()) {
            appendWrapped(sb, "    note  ", w);
        }
        reportArea.appendText(sb.toString());
    }

    /**
     * Appends {@code prefix + message}, wrapped at spaces to {@link #REPORT_COLUMNS} characters
     * with the continuation lines indented under the message, so long import notes (unit
     * decisions, replaced channels) can be read without scrolling sideways.
     */
    private static void appendWrapped(StringBuilder sb, String prefix, String message) {
        String indent = " ".repeat(prefix.length());
        int width = Math.max(20, REPORT_COLUMNS - prefix.length());
        String lead = prefix;
        for (String paragraph : message.split("\n", -1)) {
            StringBuilder line = new StringBuilder(lead);
            int used = 0;
            for (String word : paragraph.split(" ")) {
                if (used > 0 && used + 1 + word.length() > width) {
                    sb.append(line).append('\n');
                    line = new StringBuilder(indent);
                    used = 0;
                }
                if (used > 0) {
                    line.append(' ');
                    used++;
                }
                line.append(word);
                used += word.length();
            }
            sb.append(line).append('\n');
            lead = indent;
        }
    }

    private void exportCurrentView() {
        Node node = currentView();
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
            showView(0);
        });
        steps.add(() -> {
            save(stage.getScene().getRoot(), dir.resolve("desktop-window.png"));
            save(currentView(), dir.resolve("desktop-lap-analysis.png"));
            save(lapView.trackMap(), dir.resolve("desktop-track-map-speed.png"));
            showView(1);
            compareView.compare();
        });
        steps.add(() -> {
            save(compareView.node(), dir.resolve("desktop-lap-comparison.png"));
            save(compareView.charts(), dir.resolve("desktop-compare-charts.png"));
            save(compareView.trackMap(), dir.resolve("desktop-track-map-delta.png"));
            showView(2);
        });
        steps.add(() -> {
            save(currentView(), dir.resolve("desktop-import-report.png"));
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
