package org.telegram.cogram;

import android.content.Context;

/** Builds without a map engine leave the Maps tab on its empty state. */
public final class MapEngines {

    private MapEngines() {}

    /** Whether this build has a map engine, so the Maps tab can show a loading state instead of the empty one. */
    public static boolean isAvailable() {
        return false;
    }

    public static MapEngine create(Context context) {
        return null;
    }
}
