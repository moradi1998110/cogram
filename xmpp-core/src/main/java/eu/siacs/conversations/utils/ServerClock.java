package eu.siacs.conversations.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The server's clock, as seen from this phone. Phones on a network without the internet often have
 * no network time and run minutes, hours or days off, while the server stamps every message it kept
 * (archive, offline storage) with its own time. Mixing both put messages on wrong dates and out of
 * order, so every time the app stores is taken on the server's clock: the phone's clock plus the
 * offset measured against the server when connecting (XEP-0202), kept across restarts.
 */
public final class ServerClock {

    private static final String PREFERENCES = "server_clock";
    private static final String KEY_OFFSET = "offset_ms";
    /** Differences below this are measuring noise, and keeping them would only reorder near messages. */
    private static final long NOISE_MS = 2_000;

    private static volatile long offsetMs;
    private static volatile boolean loaded;

    private ServerClock() {}

    /** The current time on the server's clock, in milliseconds. */
    public static long now() {
        return System.currentTimeMillis() + offsetMs;
    }

    public static long offset() {
        return offsetMs;
    }

    /** Takes the offset measured in an earlier run, so the first messages after a start use it too. */
    public static void load(final Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        offsetMs = preferences(context).getLong(KEY_OFFSET, 0);
    }

    /**
     * Records a reading of the server's clock.
     *
     * @param serverMillis the time the server answered with
     * @param sentAtMillis the phone's clock when the question was sent
     * @param roundTripMillis how long the answer took to come back
     */
    public static void measured(
            final Context context,
            final long serverMillis,
            final long sentAtMillis,
            final long roundTripMillis) {
        final long offset = serverMillis - sentAtMillis - roundTripMillis / 2;
        final long kept = Math.abs(offset) < NOISE_MS ? 0 : offset;
        offsetMs = kept;
        loaded = true;
        preferences(context).edit().putLong(KEY_OFFSET, kept).apply();
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
