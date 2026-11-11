package org.telegram.cogram;

import android.content.Context;

/** Builds without a map engine leave the Maps tab on its empty state. */
public final class MapEngines {

    private MapEngines() {}

    public static MapEngine create(Context context) {
        return null;
    }
}
