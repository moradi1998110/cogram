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

    // The trip's figures as a number and a unit apart. Telegram's rolling digits draw their text piece by piece and lose
    // the order of a number inside Arabic text ("5:25 م" showed as "525:", "1.1 كم" as "11, كم"); the number alone
    // rolls, in digits and Latin punctuation only, and the unit is ordinary text beside it.

    /** A distance: {"1.1", "كم"}, {"350", "م"}, {"42", "km"}; kilometres always with a point, never a comma. */
    static String[] distanceParts(float meters) {
        if (meters < 1000) {
            return new String[] { String.valueOf(Math.max(10, Math.round(meters / 10f) * 10)), unitOf(R.string.PhogramMapsMeters) };
        }
        if (meters < 10000) {
            return new String[] { String.format(java.util.Locale.US, "%.1f", meters / 1000f), unitOf(R.string.PhogramMapsKilometersRound) };
        }
        return new String[] { String.valueOf(Math.round(meters / 1000f)), unitOf(R.string.PhogramMapsKilometersRound) };
    }

    /** A duration: {"25", "د"} under an hour, {"1:25", "س"} from an hour on. */
    static String[] durationParts(float seconds) {
        final int minutes = Math.max(1, Math.round(seconds / 60f));
        if (minutes < 60) {
            return new String[] { String.valueOf(minutes), unitOf(R.string.PhogramMapsMinutes) };
        }
        final String hours = LocaleController.formatString(R.string.PhogramMapsHoursMinutes, 7, 8).trim();
        final String[] words = hours.split("\\s+");
        final String unit = words.length > 1 ? words[1] : "";
        return new String[] { String.format(java.util.Locale.US, "%d:%02d", minutes / 60, minutes % 60), unit };
    }

    /** An arrival time: {"5:25", "م"}, {"17:25", ""}. */
    static String[] arrivalParts(float seconds) {
        final String time = arrival(seconds);
        final java.util.regex.Matcher clock = java.util.regex.Pattern.compile("\\d{1,2}:\\d{2}").matcher(time);
        if (!clock.find()) return new String[] { time, "" };
        final String rest = (time.substring(0, clock.start()) + time.substring(clock.end())).trim();
        return new String[] { clock.group(), rest };
    }

    /** A format's unit: the text left once its number is taken out ("%1$d كم" gives "كم"). */
    private static String unitOf(int format) {
        return LocaleController.formatString(format, 0).replace("0", "").trim();
    }
}
