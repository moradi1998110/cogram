package org.telegram.cogram;

import android.content.Context;

/** Builds with OsmAnd's map engine show its map in the Maps tab. */
public final class MapEngines {

    private MapEngines() {}

    public static MapEngine create(Context context) {
        return new OsmAndMapEngine(context);
    }
}
