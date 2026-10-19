package com.telemetry.desktop;

import javafx.application.Application;

/**
 * Plain main class, so the app also starts with JavaFX on the class path (fat jar, IDE run
 * configurations), where a main class extending {@link Application} is rejected.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(TelemetryDesktopApp.class, args);
    }
}
