package com.telemetry.desktop;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;

public class TelemetryDesktopApp extends Application {

    @Override
    public void start(Stage stage) {
        stage.setTitle("Motorsport Telemetry Analyzer Desktop");

        Label info = new Label("Desktop Phase 2 client scaffold");
        TextArea output = new TextArea();
        output.setEditable(false);
        output.setPrefRowCount(12);

        Button openFile = new Button("Open telemetry file (.csv/.ld/.ldx)");
        openFile.setOnAction(evt -> {
            FileChooser chooser = new FileChooser();
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Telemetry files", "*.csv", "*.ld", "*.ldx"));
            File selected = chooser.showOpenDialog(stage);
            if (selected != null) {
                output.setText("Selected file: " + selected.getAbsolutePath() + "\n" +
                        "Next step: upload to backend API or local parser integration.");
            }
        });

        Button compareStub = new Button("Compare laps (stub)");
        compareStub.setOnAction(evt -> output.appendText("\nCompare action will call backend /api/compare in next increment."));

        VBox root = new VBox(10, info, openFile, compareStub, output);
        root.setPadding(new Insets(16));

        stage.setScene(new Scene(root, 700, 400));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
