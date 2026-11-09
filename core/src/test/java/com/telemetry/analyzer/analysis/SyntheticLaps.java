package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.TelemetryPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Builds laps from a speed profile given as a function of distance, sampled at a fixed rate.
 * Time and distance are integrated in small sub-steps, so the expected lap time is known.
 */
final class SyntheticLaps {

    private SyntheticLaps() {
    }

    record Sample(double speedKmh, double throttle, double brake, int gear) {
    }

    interface Profile {
        Sample at(double distanceM);
    }

    static LapData lap(String id, double lengthM, double sampleRateHz, boolean withDistance, Profile profile) {
        List<TelemetryPoint> points = new ArrayList<>();
        double dt = 1.0 / sampleRateHz;
        double sub = dt / 20;
        double t = 0;
        double s = 0;
        while (true) {
            Sample x = profile.at(Math.min(s, lengthM));
            points.add(new TelemetryPoint(100 + t, x.speedKmh(), x.throttle(), x.brake(), x.gear(),
                    null, null, withDistance ? Math.min(s, lengthM) : null));
            if (s >= lengthM) {
                break;
            }
            for (int i = 0; i < 20 && s < lengthM; i++) {
                double v = profile.at(s).speedKmh() / 3.6;
                double step = v * sub;
                if (s + step >= lengthM) {
                    t += (lengthM - s) / v;
                    s = lengthM;
                    break;
                }
                s += step;
                t += sub;
            }
        }
        return new LapData(id, points);
    }

    static Profile constant(double kmh) {
        return d -> new Sample(kmh, 100, 0, 4);
    }

    static Profile speed(DoubleUnaryOperator kmhOfDistance) {
        return d -> new Sample(kmhOfDistance.applyAsDouble(d), 100, 0, 4);
    }

    /** Out-lap: {@code pitKmh} until {@code pitExitM}, then {@code kmh}. */
    static Profile pitExit(double pitKmh, double pitExitM, double kmh) {
        return speed(d -> d < pitExitM ? pitKmh : kmh);
    }

    /** In-lap: {@code kmh} until {@code pitEntryM}, then {@code pitKmh} to the end of the lap. */
    static Profile pitEntry(double kmh, double pitEntryM, double pitKmh) {
        return speed(d -> d < pitEntryM ? kmh : pitKmh);
    }
}
