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

    /** What kind of place a search result or a tapped place is; it picks the place's emoji and color. */
    int KIND_OTHER = 0;
    int KIND_FOOD = 1;
    int KIND_FUEL = 2;
    int KIND_HEALTH = 3;
    int KIND_PHARMACY = 4;
    int KIND_WORSHIP = 5;
    int KIND_EDUCATION = 6;
    int KIND_MONEY = 7;
    int KIND_LODGING = 8;
    int KIND_SHOP = 9;
    int KIND_PARKING = 10;
    int KIND_CITY = 11;
    int KIND_STREET = 12;
    int KIND_PIN = 13;

    /** A place found by the search. */
    final class Place {
        public final String name;
        public final String address;
        public final double latitude;
        public final double longitude;
        /** One of the KIND_ values. */
        public final int kind;
        /** The place's type in the app's language ("Restaurant", "City"), or empty. */
        public final String type;

        public Place(String name, String address, double latitude, double longitude) {
            this(name, address, latitude, longitude, KIND_OTHER, "");
        }

        public Place(String name, String address, double latitude, double longitude, int kind, String type) {
            this.name = name;
            this.address = address;
            this.latitude = latitude;
            this.longitude = longitude;
            this.kind = kind;
            this.type = type == null ? "" : type;
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

    /** A turn on a route, where the driver is told what to do. */
    final class Maneuver {
        public static final int STRAIGHT = 0;
        public static final int SLIGHT_LEFT = 1;
        public static final int LEFT = 2;
        public static final int SHARP_LEFT = 3;
        public static final int SLIGHT_RIGHT = 4;
        public static final int RIGHT = 5;
        public static final int SHARP_RIGHT = 6;
        public static final int KEEP_LEFT = 7;
        public static final int KEEP_RIGHT = 8;
        public static final int U_TURN = 9;
        public static final int ROUNDABOUT = 10;
        public static final int ARRIVE = 11;

        public final int type;
        /** The exit to take at a roundabout, from 1; 0 otherwise. */
        public final int exit;
        /** The road the turn leads onto, or empty. */
        public final String street;
        /** Where the turn is: its index in the route's points, and its distance from the start in meters. */
        public final int pointIndex;
        /** The real angle of the turn, from the road before to the road after, in degrees (right positive); NaN unknown. */
        public float angle = Float.NaN;
        public final float distance;

        public Maneuver(int type, int exit, String street, int pointIndex, float distance) {
            this.type = type;
            this.exit = exit;
            this.street = street == null ? "" : street;
            this.pointIndex = pointIndex;
            this.distance = distance;
        }
    }

    /**
     * A route found offline: its points (latitude, longitude), its length and the time it takes by car, its turns,
     * and the main road it follows.
     */
    final class Route {
        public final java.util.List<double[]> points;
        public final float meters;
        public final float seconds;
        public final java.util.List<Maneuver> maneuvers;
        /** The road the route spends most of its length on, or empty. */
        public final String via;
        /** The distance from the start of each point, in meters. */
        public final float[] along;
        /** Where the route goes, kept for finding it again after a wrong turn. */
        public Place destination;
        /** What the preview calls it: the fastest, an alternative OsmAnd found, or the shortest by distance. */
        public int label;
        /** Its place among the routes found together, which picks its color on the map and on its card. */
        public int index;
        public static final int LABEL_FASTEST = 0;
        public static final int LABEL_ALTERNATIVE = 1;
        public static final int LABEL_SHORTEST = 2;

        /** Where each of the route's roads starts (an index in the points), its name and its speed limit in km/h (0 unknown). */
        public int[] roadStarts = new int[0];
        public String[] roadNames = new String[0];
        public float[] speedLimits = new float[0];
        /** The warnings on the way: traffic lights, level crossings, speed cameras, bumps, stops. */
        public java.util.List<Warning> warnings = new java.util.ArrayList<>();
        /** Places to stop at on the way, in order; the route passes them before the destination. */
        public java.util.List<Place> stops = new java.util.ArrayList<>();

        public Route(java.util.List<double[]> points, float meters, float seconds, java.util.List<Maneuver> maneuvers, String via) {
            this.points = points;
            this.meters = meters;
            this.seconds = seconds;
            this.maneuvers = maneuvers;
            this.via = via == null ? "" : via;
            along = new float[points.size()];
            for (int i = 1; i < along.length; i++) {
                along[i] = along[i - 1] + (float) MapEngine.distance(points.get(i - 1)[0], points.get(i - 1)[1], points.get(i)[0], points.get(i)[1]);
            }
        }
    }

    /** The direction from one point to another, in degrees clockwise from north. */
    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        final double p1 = Math.toRadians(lat1);
        final double p2 = Math.toRadians(lat2);
        final double dLon = Math.toRadians(lon2 - lon1);
        final double y = Math.sin(dLon) * Math.cos(p2);
        final double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Distance between two points on the Earth, in meters. */
    static double distance(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6371000;
        final double dLat = Math.toRadians(lat2 - lat1);
        final double dLon = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Why a route could not be shown. */
    int ROUTE_NO_LOCATION = 1;
    int ROUTE_NO_DATA = 2;
    int ROUTE_NOT_FOUND = 3;

    interface RouteCallback {
        /** On the main thread: the routes found, the best first (others are alternatives), or none with the reason. */
        void onRoutes(java.util.List<Route> routes, int error);
    }

    /**
     * Finds the ways by car from the device's position to the place, offline, and draws them on the map with the
     * first one chosen. Motorways can be avoided.
     */
    default void showRoutes(Place destination, boolean avoidMotorways, RouteCallback callback) {
        callback.onRoutes(java.util.Collections.emptyList(), ROUTE_NOT_FOUND);
    }

    /** The time by car to the place from the device's position, without drawing anything; for the place's card. */
    default void estimate(Place destination, RouteCallback callback) {
        callback.onRoutes(java.util.Collections.emptyList(), ROUTE_NOT_FOUND);
    }

    /** Draws this one of the shown routes as the chosen one. */
    default void selectRoute(int index) {
    }

    /** Takes the drawn routes off the map. */
    default void clearRoute() {
    }

    /** Where the driver is on a route, for the page's guidance. */
    final class NavigationState {
        /** The next turn, and the one after it (null at the end). */
        public Maneuver next;
        public Maneuver then;
        public float toNext;
        public float remainingMeters;
        public float remainingSeconds;
        /** 0 at the start, 1 at the end. */
        public float progress;
        public boolean arrived;
        /** A new route is being found after the driver left this one. */
        public boolean rerouting;
        public Route route;
        /** The road the driver is on, its speed limit in km/h (0 unknown), and the driver's speed. */
        public String street = "";
        public float speedLimit;
        public float speed;
        /** The next warning ahead within a short distance, and how far it is. */
        public Warning warning;
        public float toWarning;
        /** The stop just passed, if any, so the page can say so. */
        public Place passedStop;
    }

    interface NavigationListener {
        /** On the main thread, on each new position. */
        void onNavigation(NavigationState state);
    }

    /** Follows the route with the device's position: the camera follows the driver, the listener gets guidance. */
    default void startNavigation(Route route, NavigationListener listener) {
    }

    default void stopNavigation() {
    }

    /** Something on the road the driver is warned of, from OpenStreetMap's tags on the route's points. */
    final class Warning {
        public static final int TRAFFIC_LIGHTS = 0;
        public static final int LEVEL_CROSSING = 1;
        public static final int SPEED_CAMERA = 2;
        public static final int BUMP = 3;
        public static final int STOP = 4;
        public static final int PEDESTRIANS = 5;

        public final int type;
        public final int pointIndex;
        public final float distance;
        public final double latitude;
        public final double longitude;

        public Warning(int type, int pointIndex, float distance, double latitude, double longitude) {
            this.type = type;
            this.pointIndex = pointIndex;
            this.distance = distance;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    /** A place near the route: how far ahead along the route it is, in meters. */
    final class AlongPlace {
        public final Place place;
        public final float ahead;

        public AlongPlace(Place place, float ahead) {
            this.place = place;
            this.ahead = ahead;
        }
    }

    interface AlongCallback {
        /**
         * On the main thread: the places near the route ahead of the driver found so far, nearest first. Called as the
         * search goes along the route, the nearest first; finished on the last call.
         */
        void onPlaces(java.util.List<AlongPlace> places, boolean finished);
    }

    /**
     * Shows places of this kind near the route ahead, to the destination, on the map as pins on the road (as many as
     * fit at the map's zoom, the nearest first); KIND_OTHER clears them.
     */
    default void showAlongRoute(int kind, AlongCallback callback) {
        callback.onPlaces(java.util.Collections.emptyList(), true);
    }

    /** Adds a stop on the way, finding the route again through it. */
    default void addStop(Place stop) {
    }

    /** The whole route in view, the camera no longer following the driver. */
    default void showOverview() {
    }

    /** Back to following the driver, zoomed in and tilted. */
    default void recenter() {
    }

    /** Called when the camera stops following the driver (the user moved the map), so the page offers to re-center. */
    default void setTrackingListener(Runnable onDismissed) {
    }

    /** The device's last known position (latitude, longitude), or null. */
    default double[] getPosition() {
        return null;
    }

    interface MapTapListener {
        /** A named place drawn on the map was tapped. */
        void onPlaceTapped(Place place);

        /** The map was pressed and held at this point. */
        void onLongPressed(double latitude, double longitude);

        /** One of the drawn routes was tapped: its place among the routes shown. */
        default void onRouteTapped(int index) {
        }

        /** A place pin along the route was tapped, while navigating. */
        default void onAlongTapped(Place place) {
        }
    }

    default void setMapTapListener(MapTapListener listener) {
    }

    void onResume();

    void onPause();

    void onDestroy();
}
