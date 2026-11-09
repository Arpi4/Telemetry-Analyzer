package com.telemetry.analyzer.parser;

import java.util.HashMap;
import java.util.Map;

/**
 * Boxed values of the optional vehicle-dynamics channels within one import. Equal values share one
 * {@link Double} object: these channels repeat few distinct values (ACC writes G_LON in whole g,
 * steering in whole degrees, rpm in whole 1/min), so a long session keeps a few thousand boxes
 * instead of one per sample and channel. Non-finite values become {@code null}, as they are not
 * valid measurements and have no JSON number form.
 */
final class DoublePool {

    /** Above this many distinct values new ones are boxed as usual, which bounds the pool itself. */
    private static final int MAX_DISTINCT_VALUES = 1 << 16;

    private final Map<Double, Double> values = new HashMap<>();

    Double share(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        Double shared = values.get(value);
        if (shared != null) {
            return shared;
        }
        if (values.size() < MAX_DISTINCT_VALUES) {
            values.put(value, value);
        }
        return value;
    }
}
