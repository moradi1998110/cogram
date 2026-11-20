package org.telegram.cogram;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/** Distances, durations and arrival times on the Maps tab, in the app's language. */
final class MapsFormat {

    private MapsFormat() {}

    static String distance(float meters) {
        if (meters < 1000) {
            return LocaleController.formatString(R.string.PhogramMapsMeters, Math.max(10, Math.round(meters / 10f) * 10));
        }
        if (meters < 10000) {
            return LocaleController.formatString(R.string.PhogramMapsKilometers, meters / 1000f);
        }
        return LocaleController.formatString(R.string.PhogramMapsKilometersRound, Math.round(meters / 1000f));
    }

    static String duration(float seconds) {
        final int minutes = Math.max(1, Math.round(seconds / 60f));
        if (minutes < 60) {
            return LocaleController.formatString(R.string.PhogramMapsMinutes, minutes);
        }
        return LocaleController.formatString(R.string.PhogramMapsHoursMinutes, minutes / 60, minutes % 60);
    }

    /** The clock time after this many seconds from now, as Telegram writes times. */
    static String arrival(float seconds) {
        return LocaleController.getInstance().getFormatterDay().format(System.currentTimeMillis() + (long) (seconds * 1000));
    }
}
