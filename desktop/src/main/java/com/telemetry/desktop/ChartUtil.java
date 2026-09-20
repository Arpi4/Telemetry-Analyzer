package com.telemetry.desktop;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import javafx.collections.FXCollections;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;

import java.util.List;
import java.util.function.ToDoubleFunction;

final class ChartUtil {

    static final int MAX_POINTS = 4_000;

    private ChartUtil() {
    }

    static LineChart<Number, Number> lineChart(String xLabel, String yLabel) {
        NumberAxis x = new NumberAxis();
        x.setLabel(xLabel);
        x.setAnimated(false);
        NumberAxis y = new NumberAxis();
        y.setLabel(yLabel);
        y.setAnimated(false);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(true);
        chart.getStyleClass().add("telemetry-chart");
        return chart;
    }

    static XYChart.Series<Number, Number> series(
            String name,
            List<TelemetryPoint> points,
            ToDoubleFunction<TelemetryPoint> yValue
    ) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(name);
        if (points == null || points.isEmpty()) {
            return series;
        }
        int step = Math.max(1, points.size() / MAX_POINTS);
        var data = FXCollections.<XYChart.Data<Number, Number>>observableArrayList();
        for (int i = 0; i < points.size(); i += step) {
            TelemetryPoint p = points.get(i);
            data.add(new XYChart.Data<>(xOf(p, i), yValue.applyAsDouble(p)));
        }
        series.setData(data);
        return series;
    }

    static XYChart.Series<Number, Number> gpsSeries(String name, List<TelemetryPoint> points) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(name);
        if (points == null) {
            return series;
        }
        int step = Math.max(1, points.size() / MAX_POINTS);
        var data = FXCollections.<XYChart.Data<Number, Number>>observableArrayList();
        for (int i = 0; i < points.size(); i += step) {
            TelemetryPoint p = points.get(i);
            if (p.longitude() != null && p.latitude() != null) {
                data.add(new XYChart.Data<>(p.longitude(), p.latitude()));
            }
        }
        series.setData(data);
        return series;
    }

    static boolean hasGps(LapData lap) {
        if (lap == null) {
            return false;
        }
        return lap.points().stream().anyMatch(p -> p.latitude() != null && p.longitude() != null);
    }

    static double lapDurationSeconds(LapData lap) {
        if (lap == null || lap.points().size() < 2) {
            return 0;
        }
        double t0 = lap.points().getFirst().timestamp();
        double t1 = lap.points().getLast().timestamp();
        return Math.max(0, t1 - t0);
    }

    static String formatLapTime(double seconds) {
        if (seconds <= 0) {
            return "n/a";
        }
        int min = (int) (seconds / 60);
        double rem = seconds - min * 60;
        return String.format("%d:%06.3f", min, rem);
    }

    private static double xOf(TelemetryPoint p, int index) {
        if (p.distanceMeters() != null) {
            return p.distanceMeters();
        }
        return p.timestamp() != 0 ? p.timestamp() : index;
    }
}
