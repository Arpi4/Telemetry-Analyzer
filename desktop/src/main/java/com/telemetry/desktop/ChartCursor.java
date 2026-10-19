package com.telemetry.desktop;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Line;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Linked cursor for charts sharing the lap distance axis: hovering any chart moves a vertical line
 * across all of them and reports the distance to a listener (readout, track map marker).
 */
final class ChartCursor {

    private record Entry(LineChart<Number, Number> chart, StackPane wrapper, Line line) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final DoubleConsumer listener;

    ChartCursor(DoubleConsumer listener) {
        this.listener = listener;
    }

    /** Wraps the chart so the cursor line can be overlaid; use the returned node in the layout. */
    StackPane attach(LineChart<Number, Number> chart) {
        Line line = new Line();
        line.getStyleClass().add("chart-cursor");
        line.setManaged(false);
        line.setMouseTransparent(true);
        line.setVisible(false);
        StackPane wrapper = new StackPane(chart, line);
        entries.add(new Entry(chart, wrapper, line));
        chart.setOnMouseMoved(e -> {
            NumberAxis axis = (NumberAxis) chart.getXAxis();
            double local = axis.sceneToLocal(e.getSceneX(), e.getSceneY()).getX();
            double value = axis.getValueForDisplay(local).doubleValue();
            if (value >= axis.getLowerBound() && value <= axis.getUpperBound()) {
                listener.accept(value);
            }
        });
        chart.setOnMouseExited(e -> listener.accept(Double.NaN));
        return wrapper;
    }

    void show(double distance) {
        for (Entry e : entries) {
            NumberAxis axis = (NumberAxis) e.chart().getXAxis();
            Node plot = e.chart().lookup(".chart-plot-background");
            if (Double.isNaN(distance) || plot == null
                    || distance < axis.getLowerBound() || distance > axis.getUpperBound()) {
                e.line().setVisible(false);
                continue;
            }
            Point2D scene = axis.localToScene(axis.getDisplayPosition(distance), 0);
            double x = e.wrapper().sceneToLocal(scene).getX();
            Bounds plotBounds = e.wrapper().sceneToLocal(plot.localToScene(plot.getBoundsInLocal()));
            e.line().setStartX(x);
            e.line().setEndX(x);
            e.line().setStartY(plotBounds.getMinY());
            e.line().setEndY(plotBounds.getMaxY());
            e.line().setVisible(true);
        }
    }
}
