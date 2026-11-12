package org.telegram.cogram;

import android.view.View;

/** A map shown in Phogram's Maps tab, with the page's lifecycle passed on to it. */
public interface MapEngine {

    View getView();

    /** Sets how much of each edge the page's header and tab bar cover, so the map keeps its controls visible. */
    void setPadding(int left, int top, int right, int bottom);

    /** Moves the map to the device's position and keeps it there; the caller has the location permission. */
    void moveToMyLocation();

    /** Tells the map that the location permission has just been granted, so it can start following the device. */
    void onLocationPermissionGranted();

    /** The map's rotation in degrees, 0 when north is up. */
    float getRotation();

    /** Turns the map back to north up. */
    void resetRotation();

    void onResume();

    void onPause();

    void onDestroy();
}
