package com.telemetry.analyzer.analysis;

/**
 * Piecewise linear interpolation over a non-decreasing axis.
 */
public final class Interpolation {

    private Interpolation() {
    }

    /**
     * Evaluates y(x) at every grid position. The grid must be ascending. Where x has a plateau
     * (car standing still), the value of the first sample that reaches the position is used,
     * which for elapsed time means "first arrival" at that distance.
     *
     * <p>Runs in O(|x| + |grid|) with a single forward-moving cursor.
     */
    public static double[] resample(double[] x, double[] y, double[] grid) {
        double[] out = new double[grid.length];
        int n = x.length;
        if (n == 0) {
            return out;
        }
        if (n == 1) {
            java.util.Arrays.fill(out, y[0]);
            return out;
        }
        int j = 0;
        for (int k = 0; k < grid.length; k++) {
            double s = grid[k];
            while (j < n - 2 && x[j + 1] < s) {
                j++;
            }
            double x0 = x[j];
            double x1 = x[j + 1];
            if (s <= x0) {
                out[k] = y[j];
            } else if (s >= x1) {
                out[k] = y[j + 1];
            } else {
                double f = (s - x0) / (x1 - x0);
                out[k] = y[j] + f * (y[j + 1] - y[j]);
            }
        }
        return out;
    }

    /** 0, step, 2·step, … up to and including length (the last cell may be shorter). */
    public static double[] grid(double length, double step) {
        if (length <= 0 || step <= 0) {
            return new double[]{0};
        }
        int cells = (int) Math.ceil(length / step - 1e-9);
        double[] g = new double[cells + 1];
        for (int k = 0; k < cells; k++) {
            g[k] = k * step;
        }
        g[cells] = length;
        return g;
    }

    public static double[] scale(double[] values, double factor) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] * factor;
        }
        return out;
    }
}
