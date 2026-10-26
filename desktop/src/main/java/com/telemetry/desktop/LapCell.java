package com.telemetry.desktop;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.desktop.SessionModel.LapAnalysis;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;

/**
 * Lap entry of the lap list and the lap pickers: lap id, a fastest / partial badge and the lap
 * time. The fastest lap is purple as on timing screens, partial laps are dimmed.
 */
final class LapCell extends ListCell<LapData> {

    private final SessionModel model;
    private final boolean badges;
    private final Label id = new Label();
    private final Label badge = new Label();
    private final Label time = new Label();
    private final HBox box = new HBox(8, id, Ui.spacer(), badge, time);

    /** {@code badges = false} keeps only the colours, for the narrow button of a lap picker. */
    LapCell(SessionModel model, boolean badges) {
        this.model = model;
        this.badges = badges;
        id.getStyleClass().add("lap-id");
        badge.getStyleClass().add("lap-badge");
        badge.managedProperty().bind(badge.visibleProperty());
        time.getStyleClass().add("lap-time");
        box.setAlignment(Pos.CENTER_LEFT);
    }

    @Override
    protected void updateItem(LapData lap, boolean empty) {
        super.updateItem(lap, empty);
        getStyleClass().removeAll("lap-fastest", "lap-incomplete");
        setText(null);
        if (empty || lap == null) {
            setGraphic(null);
            return;
        }
        id.setText(lap.lapId());
        SessionData owner = model.sessionOf(lap);
        LapAnalysis a = owner == null ? null : model.analyze(owner, lap);
        time.setText(a == null ? "" : Formats.lapTime(a.trace().lapTime()));
        boolean fastest = a != null && a.fastest();
        boolean partial = a != null && !a.complete();
        badge.setText(fastest ? "FASTEST" : "PARTIAL");
        badge.setVisible(badges && (fastest || partial));
        if (fastest) {
            getStyleClass().add("lap-fastest");
        } else if (partial) {
            getStyleClass().add("lap-incomplete");
        }
        setGraphic(box);
    }
}
