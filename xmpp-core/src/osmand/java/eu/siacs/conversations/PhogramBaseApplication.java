package eu.siacs.conversations;

import android.os.Looper;

import net.osmand.plus.OsmandApplication;

/**
 * The application class Phogram's own one builds on, in builds with OsmAnd's map engine. OsmAnd's code
 * reaches its services through its application class, so the app has to be one.
 *
 * OsmAnd's start-up takes about a second of the main thread, so it does not run when the app starts but the
 * first time the Maps tab needs it ({@link #startOsmAnd}). Until then the app is a plain Android application:
 * OsmAnd's application methods that would reach its unstarted services are skipped.
 */
public abstract class PhogramBaseApplication extends OsmandApplication {

    private volatile boolean osmAndStarted;

    @Override
    public void onCreate() {
        // Android's own Application.onCreate does nothing, so leaving OsmAnd's out loses nothing.
    }

    /** Starts OsmAnd's services once; later calls do nothing. Runs on the main thread, as OsmAnd's start-up must. */
    public final void startOsmAnd() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("OsmAnd starts on the main thread");
        }
        if (osmAndStarted) {
            return;
        }
        osmAndStarted = true;
        super.onCreate();
    }

    public final boolean isOsmAndStarted() {
        return osmAndStarted;
    }

    // OsmAnd's versions of these first pass the call on to Android, which tells the app's other listeners, and then
    // reach OsmAnd's services. Before the start those services are not made yet, so that second part fails and is
    // dropped; the listeners have already been told.

    @Override
    public void onLowMemory() {
        if (osmAndStarted) {
            super.onLowMemory();
            return;
        }
        try {
            super.onLowMemory();
        } catch (NullPointerException ignored) {
        }
    }

    @Override
    public void onTerminate() {
        if (osmAndStarted) {
            super.onTerminate();
            return;
        }
        try {
            super.onTerminate();
        } catch (NullPointerException ignored) {
        }
    }
}
