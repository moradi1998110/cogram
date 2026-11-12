package org.telegram.cogram;

import android.content.Context;

/** Builds with OsmAnd's map engine show its map in the Maps tab. */
public final class MapEngines {

    private MapEngines() {}

    public static boolean isAvailable() {
        return true;
    }

    /** Starts OsmAnd if it has not started yet (about a second the first time) and makes its map. */
    public static MapEngine create(Context context) {
        return new OsmAndMapEngine(context);
    }
}
