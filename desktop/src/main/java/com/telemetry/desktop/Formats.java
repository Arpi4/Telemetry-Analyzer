package com.telemetry.desktop;

import java.util.Locale;

final class Formats {

    private Formats() {
    }

    static String lapTime(double seconds) {
        if (!(seconds > 0)) {
            return "n/a";
        }
        int min = (int) (seconds / 60);
        return String.format(Locale.ROOT, "%d:%06.3f", min, seconds - min * 60);
    }

    /** Signed seconds; positive = slower than the reference. */
    static String delta(double seconds) {
        return String.format(Locale.ROOT, "%+.3f s", seconds);
    }

    static String number(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    static String signed(double value, int decimals) {
        return String.format(Locale.ROOT, "%+." + decimals + "f", value);
    }
}
