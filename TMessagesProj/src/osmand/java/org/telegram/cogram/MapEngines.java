package org.telegram.cogram;

import android.content.Context;

/**
 * Builds with OsmAnd show Phogram's map in the Maps tab: MapLibre draws it smoothly from vector tiles on the phone,
 * and OsmAnd, without its own map, searches its offline map files.
 */
public final class MapEngines {

    private MapEngines() {}

    public static boolean isAvailable() {
        return true;
    }

    /** Starts OsmAnd if it has not started yet (about a second the first time) and makes the map. */
    public static MapEngine create(Context context) {
        return new MapLibreMapEngine(context);
    }
}
