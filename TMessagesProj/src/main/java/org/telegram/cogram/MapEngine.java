package org.telegram.cogram;

import android.view.View;

/** A map shown in Phogram's Maps tab, with the page's lifecycle passed on to it. */
public interface MapEngine {

    View getView();

    /**
     * Runs the listener on the main thread once the map can draw, right away if it already can. Until then the
     * map's view shows nothing, so the page keeps its progress circle.
     */
    void setOnReadyListener(Runnable listener);

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

    /** A map file the page offers to download: the world map first, then the region at the map's center. */
    final class Download {
        public final String name;
        public final String size;
        public final boolean downloading;
        /** 0 to 100 while downloading. */
        public final float progress;

        public Download(String name, String size, boolean downloading, float progress) {
            this.name = name;
            this.size = size;
            this.downloading = downloading;
            this.progress = progress;
        }
    }

    /** Called on the main thread when the offered download or its progress changes. */
    void setDownloadListener(Runnable listener);

    /** The map file to offer now, or null when the map has what it needs here. */
    Download getOfferedDownload();

    void startOfferedDownload();

    void cancelOfferedDownload();

    void onResume();

    void onPause();

    void onDestroy();
}
