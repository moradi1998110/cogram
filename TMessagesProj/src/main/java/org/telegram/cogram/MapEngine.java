package org.telegram.cogram;

import android.view.View;

/** A map shown in Phogram's Maps tab, with the page's lifecycle passed on to it. */
public interface MapEngine {

    View getView();

    /** Sets how much of each edge the page's header and tab bar cover, so the map keeps its controls visible. */
    void setPadding(int left, int top, int right, int bottom);

    void onResume();

    void onPause();

    void onDestroy();
}
