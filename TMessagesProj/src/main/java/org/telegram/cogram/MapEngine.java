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

    /**
     * Runs the listener on the main thread whenever the camera moves or stops, so the page follows the rotation
     * without checking it on a timer. False when this map cannot tell, and the page checks on a timer instead.
     */
    default boolean setCameraMoveListener(Runnable listener) {
        return false;
    }

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

    /** A place found by the search. */
    final class Place {
        public final String name;
        public final String address;
        public final double latitude;
        public final double longitude;

        public Place(String name, String address, double latitude, double longitude) {
            this.name = name;
            this.address = address;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    interface SearchCallback {
        /** On the main thread, for the latest query only. */
        void onResults(String query, java.util.List<Place> places);
    }

    /** Searches the downloaded maps around the map's center, offline. */
    void search(String query, SearchCallback callback);

    interface PinListener {
        /** On the main thread: where the shown place is on the map view, in pixels. */
        void onPinMoved(float x, float y);
    }

    /** Moves the map to the place and reports where it is as the map moves; null stops that. */
    void showPlace(Place place, PinListener listener);

    /** False until the region's search data is on the phone, so the page can say what to download. */
    default boolean hasSearchData() {
        return true;
    }

    /** A route found offline: its points (latitude, longitude), its length and the time it takes by car. */
    final class Route {
        public final java.util.List<double[]> points;
        public final float meters;
        public final float seconds;

        public Route(java.util.List<double[]> points, float meters, float seconds) {
            this.points = points;
            this.meters = meters;
            this.seconds = seconds;
        }
    }

    /** Why a route could not be shown. */
    int ROUTE_NO_LOCATION = 1;
    int ROUTE_NO_DATA = 2;
    int ROUTE_NOT_FOUND = 3;

    interface RouteCallback {
        /** On the main thread: the route drawn on the map, or null with the reason. */
        void onRoute(Route route, int error);
    }

    /** Finds the way by car from the device's position to the place, offline, and draws it on the map. */
    default void showRoute(Place destination, RouteCallback callback) {
        callback.onRoute(null, ROUTE_NOT_FOUND);
    }

    /** Takes the drawn route off the map. */
    default void clearRoute() {
    }

    interface MapTapListener {
        /** A named place drawn on the map was tapped. */
        void onPlaceTapped(Place place);

        /** The map was pressed and held at this point. */
        void onLongPressed(double latitude, double longitude);
    }

    default void setMapTapListener(MapTapListener listener) {
    }

    void onResume();

    void onPause();

    void onDestroy();
}
