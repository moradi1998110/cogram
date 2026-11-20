package org.telegram.cogram;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.location.Location;
import android.view.View;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Geometry;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Phogram's map drawn by MapLibre on the GPU, so it pans, zooms and turns smoothly, from vector tiles on the phone
 * (a PMTiles file generated from OpenStreetMap) in Protomaps' light or dark style with Arabic names. Search and the
 * map files it needs still come from OsmAnd (OsmAndOffline), which works offline; OsmAnd's own map is not drawn.
 */
final class MapLibreMapEngine implements MapEngine {

    private static final double START_LATITUDE = 33.3;
    private static final double START_LONGITUDE = 44.4;
    private static final double START_ZOOM = 5.5;
    private static final double MY_LOCATION_ZOOM = 15;
    private static final double PLACE_ZOOM = 16;
    private static final int CAMERA_MS = 600;

    /**
     * A low-zoom overview of the region ships in the app, so the map is never empty; the region's detailed tiles are
     * downloaded after installation (MapData). The overview is copied out once: MapLibre reads tiles by range.
     */
    private static final String OVERVIEW_ASSET = "phogram_map/iraq-overview.pmtiles";
    private static final String OVERVIEW_FILE = "maps/iraq-overview.pmtiles";

    /**
     * OsmAnd (search and its map files) starts only once the map has been still this long, or when the user
     * searches, so its start never competes with the first pans of the map.
     */
    private static final long OFFLINE_START_DELAY_MS = 5000;

    private final Context context;
    private final MapView view;
    private OsmAndOffline offline;
    private Runnable cameraListener;
    private final Runnable startOffline = this::offline;
    private MapLibreMap map;
    private Style style;
    private boolean destroyed;
    private boolean started;
    private boolean resumed;
    private boolean ready;
    private Runnable readyListener;

    private Place shownPlace;
    private PinListener pinListener;
    private Runnable downloadListener;
    private File currentTiles;

    MapLibreMapEngine(Context context) {
        this.context = context;
        MapLibre.getInstance(context.getApplicationContext());
        // Labels of the tiles and of the device's position are placed apart, which spares the collision work
        // on every frame of a pan; tiles around the view and at lower zooms are fetched ahead (MapLibre's default).
        // Until the tiles draw, the map shows the theme's background instead of MapLibre's beige.
        final MapLibreMapOptions options = MapLibreMapOptions.createFromAttributes(context)
                .crossSourceCollisions(false)
                .setPrefetchZoomDelta(4)
                .foregroundLoadColor(Theme.getColor(Theme.key_windowBackgroundGray));
        view = new MapView(context, options);
        view.onCreate(null);
        MapData.addListener(mapDataChanged);
        MapData.refreshCatalog();
        MapData.startPolling();
        prepareTiles(file -> {
            if (destroyed) return;
            currentTiles = file;
            view.getMapAsync(map -> onMap(map, file));
        });
    }

    /** The region's downloaded tiles, or else the overview, copied out of the assets off the main thread once. */
    private void prepareTiles(Utilities.Callback<File> done) {
        final File installed = MapData.installedTiles();
        if (installed != null) {
            done.run(installed);
            return;
        }
        final File file = new File(context.getFilesDir(), OVERVIEW_FILE);
        if (file.exists() && file.length() > 0) {
            done.run(file);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            final File temp = new File(file.getPath() + ".tmp");
            try {
                file.getParentFile().mkdirs();
                try (InputStream in = context.getAssets().open(OVERVIEW_ASSET); OutputStream out = new FileOutputStream(temp)) {
                    final byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                if (!temp.renameTo(file)) {
                    FileLog.e("cogram: could not place the map tiles");
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> done.run(file));
        });
    }

    private void onMap(MapLibreMap map, File tiles) {
        if (destroyed) return;
        this.map = map;
        map.getUiSettings().setLogoEnabled(false);
        // The page has its own compass button, as Telegram's location page does.
        map.getUiSettings().setCompassEnabled(false);
        map.getUiSettings().setAttributionEnabled(true);
        applyAttributionMargins();
        map.setMaxZoomPreference(19);
        map.setCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(START_LATITUDE, START_LONGITUDE)).zoom(START_ZOOM).build());
        map.addOnCameraMoveStartedListener(reason -> AndroidUtilities.cancelRunOnUIThread(startOffline));
        map.addOnMapClickListener(this::onMapClick);
        map.addOnMapLongClickListener(point -> {
            if (tapListener == null) return false;
            tapListener.onLongPressed(point.getLatitude(), point.getLongitude());
            return true;
        });
        map.addOnCameraMoveListener(this::onCameraMoved);
        map.addOnCameraIdleListener(this::onCameraIdle);
        // MapLibre draws on its own GPU surface, which Android's frame statistics do not see, so test builds have it
        // report its frame rate; the emulator check reads these lines to compare the map's smoothness.
        if (org.telegram.messenger.BuildVars.DEBUG_VERSION) {
            map.setOnFpsChangedListener(fps -> android.util.Log.d("PhogramMapFps", String.format(java.util.Locale.US, "%.1f", fps)));
        }
        map.setStyle(new Style.Builder().fromJson(styleJson(tiles)), loaded -> {
            if (destroyed) return;
            style = loaded;
            activateLocation();
            drawRoute();
            ready = true;
            if (readyListener != null) {
                readyListener.run();
            }
            onCameraIdle();
        });
    }

    /** Protomaps' style for the app's theme, pointed at the tiles file on the phone. */
    private String styleJson(File tiles) {
        final String name = Theme.isCurrentThemeDark() ? "phogram_map/style-dark.json" : "phogram_map/style-light.json";
        String json = "";
        try (InputStream in = context.getAssets().open(name)) {
            final byte[] bytes = new byte[in.available()];
            int read = 0;
            while (read < bytes.length) {
                final int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            json = new String(bytes, 0, read, "UTF-8");
        } catch (Exception e) {
            FileLog.e(e);
        }
        return json.replace("pmtiles://PHOGRAM_TILES", "pmtiles://file://" + tiles.getAbsolutePath());
    }

    private boolean hasLocationPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** The blue dot, from Android's own location service (Phogram ships no Google location). */
    @SuppressWarnings("MissingPermission")
    private void activateLocation() {
        if (map == null || style == null || !hasLocationPermission()) return;
        final LocationComponent location = map.getLocationComponent();
        if (!location.isLocationComponentActivated()) {
            location.activateLocationComponent(LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(true).build());
        }
        location.setLocationComponentEnabled(resumed);
        location.setRenderMode(RenderMode.NORMAL);
    }

    @Override
    public View getView() {
        return view;
    }

    @Override
    public void setOnReadyListener(Runnable listener) {
        readyListener = listener;
        if (ready && listener != null) {
            listener.run();
        }
    }

    private int attributionBottom;

    /**
     * The map runs under the status bar and the tab bar, and its center stays the view's, as the OsmAnd map's did:
     * setting MapLibre's camera padding would also stop a camera move under way, such as the flight to a place the
     * card has just opened for. Only OpenStreetMap's credit button moves up, out from under the bars.
     */
    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        attributionBottom = bottom;
        applyAttributionMargins();
    }

    private void applyAttributionMargins() {
        if (map != null) {
            map.getUiSettings().setAttributionMargins(AndroidUtilities.dp(12), 0, 0, attributionBottom);
        }
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void moveToMyLocation() {
        activateLocation();
        if (map == null || !map.getLocationComponent().isLocationComponentActivated()) return;
        final LocationComponent location = map.getLocationComponent();
        // Follows the position from here on, until the user moves the map, as Telegram's location page does.
        location.setCameraMode(CameraMode.TRACKING);
        final Location last = location.getLastKnownLocation();
        if (last != null) {
            flyTo(last);
            return;
        }
        // MapLibre asks Android's fused provider, which may have no fix stored yet; the GPS's last fix still moves
        // the map there at once, as Telegram's button does.
        org.telegram.messenger.ApplicationLoader.getLocationServiceProvider().getLastLocation(fix -> {
            if (fix != null) {
                AndroidUtilities.runOnUIThread(() -> flyTo(fix));
            }
        });
    }

    private void flyTo(Location fix) {
        if (map == null || destroyed) return;
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(fix.getLatitude(), fix.getLongitude()),
                Math.max(map.getCameraPosition().zoom, MY_LOCATION_ZOOM)), CAMERA_MS);
    }

    @Override
    public void onLocationPermissionGranted() {
        activateLocation();
    }

    @Override
    public float getRotation() {
        return map == null ? 0 : (float) map.getCameraPosition().bearing;
    }

    @Override
    public void resetRotation() {
        if (map != null) {
            map.animateCamera(CameraUpdateFactory.bearingTo(0), CAMERA_MS);
        }
    }

    // Downloads and search: OsmAnd's map files, which the offline search reads.

    /** OsmAnd, started the first time it is needed. */
    private OsmAndOffline offline() {
        if (offline == null && !destroyed) {
            AndroidUtilities.cancelRunOnUIThread(startOffline);
            offline = new OsmAndOffline(context);
            offline.setDownloadListener(downloadListener);
            if (map != null) {
                final CameraPosition camera = map.getCameraPosition();
                offline.setCenter(camera.target.getLatitude(), camera.target.getLongitude(), camera.zoom);
            }
        }
        return offline;
    }

    @Override
    public void setDownloadListener(Runnable listener) {
        downloadListener = listener;
        if (offline != null) {
            offline.setDownloadListener(listener);
        }
    }

    @Override
    public boolean setCameraMoveListener(Runnable listener) {
        cameraListener = listener;
        return true;
    }

    /**
     * The region's map tiles come first (MapData, from the internet or the organization's server); once they are on
     * the phone, OsmAnd's map files for the offline search.
     */
    @Override
    public Download getOfferedDownload() {
        final MapData.RegionFile tiles = MapData.offer();
        if (tiles != null) {
            return new Download(LocaleController.formatString(R.string.PhogramMapsRegionMap, tiles.regionName),
                    AndroidUtilities.formatFileSize(tiles.size), MapData.isDownloading(), MapData.getProgress());
        }
        return offline != null ? offline.getOfferedDownload() : null;
    }

    @Override
    public void startOfferedDownload() {
        final MapData.RegionFile tiles = MapData.offer();
        if (tiles != null) {
            MapData.download(tiles);
        } else if (offline != null) {
            offline.startOfferedDownload();
        }
    }

    @Override
    public void cancelOfferedDownload() {
        if (MapData.offer() != null) {
            MapData.cancel();
        } else if (offline != null) {
            offline.cancelOfferedDownload();
        }
    }

    /** A finished download switches the map from the overview to the region's tiles. */
    private final Runnable mapDataChanged = () -> {
        if (destroyed) return;
        final File installed = MapData.installedTiles();
        if (map != null && installed != null && !installed.equals(currentTiles)) {
            currentTiles = installed;
            map.setStyle(new Style.Builder().fromJson(styleJson(installed)), loaded -> {
                if (destroyed) return;
                style = loaded;
                activateLocation();
                drawRoute();
            });
        }
        if (downloadListener != null) {
            downloadListener.run();
        }
    };

    // Taps on the map: a named place opens its card; pressing and holding drops a pin there.

    private MapTapListener tapListener;
    /** The style's layers with named places, the ones a tap looks for, nearest first. */
    private static final String[] TAPPABLE_LAYERS = { "pois", "places_subplace", "places_locality", "places_region" };

    @Override
    public void setMapTapListener(MapTapListener listener) {
        tapListener = listener;
    }

    private boolean onMapClick(LatLng point) {
        if (tapListener == null || map == null || style == null) return false;
        final PointF screen = map.getProjection().toScreenLocation(point);
        if (onRouteClick(screen)) return true;
        final float slop = AndroidUtilities.dp(12);
        final List<Feature> features = map.queryRenderedFeatures(
                new android.graphics.RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop), TAPPABLE_LAYERS);
        final String language = LocaleController.getInstance().getCurrentLocale().getLanguage();
        for (Feature feature : features) {
            String name = feature.hasProperty("name:" + language) ? feature.getStringProperty("name:" + language) : null;
            if (name == null || name.isEmpty()) {
                name = feature.hasProperty("name") ? feature.getStringProperty("name") : null;
            }
            final Geometry geometry = feature.geometry();
            if (name == null || name.isEmpty() || !(geometry instanceof Point)) continue;
            final Point at = (Point) geometry;
            final String kind = feature.hasProperty("kind") ? feature.getStringProperty("kind") : "";
            tapListener.onPlaceTapped(new Place(name,
                    String.format(java.util.Locale.US, "%.5f, %.5f", at.latitude(), at.longitude()), at.latitude(), at.longitude(),
                    kindOfTile(kind), ""));
            return true;
        }
        return false;
    }

    /** The kind of a place drawn on the map, from the tile's "kind" (OpenStreetMap's tag, as Protomaps keeps it). */
    private static int kindOfTile(String kind) {
        switch (kind) {
            case "locality":
            case "neighbourhood":
            case "macrohood":
            case "region":
                return KIND_CITY;
            case "restaurant":
            case "fast_food":
            case "cafe":
            case "food_court":
            case "bakery":
                return KIND_FOOD;
            case "fuel":
            case "charging_station":
                return KIND_FUEL;
            case "hospital":
            case "clinic":
            case "doctors":
            case "dentist":
                return KIND_HEALTH;
            case "pharmacy":
                return KIND_PHARMACY;
            case "place_of_worship":
                return KIND_WORSHIP;
            case "school":
            case "university":
            case "college":
            case "kindergarten":
                return KIND_EDUCATION;
            case "bank":
            case "atm":
                return KIND_MONEY;
            case "hotel":
            case "motel":
            case "hostel":
                return KIND_LODGING;
            case "parking":
                return KIND_PARKING;
            case "supermarket":
            case "convenience":
            case "marketplace":
            case "mall":
                return KIND_SHOP;
            default:
                return KIND_OTHER;
        }
    }

    // Routes: OsmAnd's offline car router from the device's position. The chosen route is drawn in Telegram's blue over
    // a white casing, the alternatives in grey under it, all below the labels so names stay readable.

    private static final String ROUTE_SOURCE = "phogram-route";
    private static final String ROUTE_CASING = "phogram-route-casing";
    private static final String ROUTE_LINE = "phogram-route-line";
    private static final String ALTERNATIVE_SOURCE = "phogram-route-alternatives";
    private static final String ALTERNATIVE_CASING = "phogram-route-alternatives-casing";
    private static final String ALTERNATIVE_LINE = "phogram-route-alternatives-line";
    private final List<Route> shownRoutes = new ArrayList<>();
    private int chosenRoute;
    private boolean avoidMotorways;

    @Override
    public boolean hasSearchData() {
        return OsmAndOffline.installedFile() != null;
    }

    @Override
    public void showRoutes(Place destination, boolean avoidMotorways, RouteCallback callback) {
        if (OsmAndOffline.installedFile() == null) {
            callback.onRoutes(new ArrayList<>(), ROUTE_NO_DATA);
            return;
        }
        this.avoidMotorways = avoidMotorways;
        locate(here -> {
            if (destroyed) return;
            if (here == null) {
                callback.onRoutes(new ArrayList<>(), ROUTE_NO_LOCATION);
            } else {
                routeFrom(here.getLatitude(), here.getLongitude(), destination, callback);
            }
        });
    }

    /** The device's position: the map's, else the last one Android kept, else a new one from the GPS. */
    @SuppressWarnings("MissingPermission")
    private void locate(Utilities.Callback<Location> done) {
        Location here = null;
        if (map != null && map.getLocationComponent().isLocationComponentActivated()) {
            here = map.getLocationComponent().getLastKnownLocation();
        }
        if (here != null) {
            done.run(here);
            return;
        }
        if (!hasLocationPermission()) {
            done.run(null);
            return;
        }
        org.telegram.messenger.ApplicationLoader.getLocationServiceProvider().getLastLocation(fix -> AndroidUtilities.runOnUIThread(() -> {
            if (destroyed) return;
            if (fix != null) {
                done.run(fix);
            } else {
                // No position kept yet (after a restart, or with the GPS just turned on): the GPS is asked for one.
                currentLocation(done);
            }
        }));
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public double[] getPosition() {
        Location here = null;
        if (map != null && map.getLocationComponent().isLocationComponentActivated()) {
            here = map.getLocationComponent().getLastKnownLocation();
        }
        if (here == null && hasLocationPermission()) {
            final android.location.LocationManager manager =
                    (android.location.LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            for (String provider : new String[] { android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER }) {
                try {
                    final Location known = manager != null ? manager.getLastKnownLocation(provider) : null;
                    if (known != null && (here == null || known.getTime() > here.getTime())) {
                        here = known;
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        return here == null ? null : new double[] { here.getLatitude(), here.getLongitude() };
    }

    private static final long FRESH_LOCATION_TIMEOUT_MS = 20_000;

    /** A full location listener: before Android 10 the system also calls the status methods, which have no default there. */
    private abstract static class PositionListener implements android.location.LocationListener {
        @Override
        public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    }

    /** One new position from the GPS (or the network, where the phone has it), or null after twenty seconds. */
    @SuppressWarnings("MissingPermission")
    private void currentLocation(Utilities.Callback<Location> done) {
        final android.location.LocationManager manager =
                (android.location.LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null || !hasLocationPermission()) {
            done.run(null);
            return;
        }
        final boolean[] finished = { false };
        final android.location.LocationListener[] listeners = new android.location.LocationListener[1];
        final Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            if (listeners[0] != null) {
                try {
                    manager.removeUpdates(listeners[0]);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        };
        final Runnable timeout = () -> {
            if (finished[0]) return;
            finish.run();
            done.run(null);
        };
        listeners[0] = new PositionListener() {
            @Override
            public void onLocationChanged(Location location) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (finished[0]) return;
                    AndroidUtilities.cancelRunOnUIThread(timeout);
                    finish.run();
                    done.run(location);
                });
            }
        };
        boolean asked = false;
        for (String provider : new String[] { android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER }) {
            try {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 1000, 0, listeners[0], android.os.Looper.getMainLooper());
                    asked = true;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (!asked) {
            finish.run();
            done.run(null);
            return;
        }
        AndroidUtilities.runOnUIThread(timeout, FRESH_LOCATION_TIMEOUT_MS);
    }

    private void routeFrom(double latitude, double longitude, Place destination, RouteCallback callback) {
        final OsmAndOffline router = offline();
        if (router == null) {
            callback.onRoutes(new ArrayList<>(), ROUTE_NOT_FOUND);
            return;
        }
        router.route(latitude, longitude, destination.latitude, destination.longitude, true, routes -> {
            if (destroyed) return;
            if (routes.isEmpty() || routes.get(0).points.size() < 2) {
                callback.onRoutes(new ArrayList<>(), ROUTE_NOT_FOUND);
                return;
            }
            for (int i = 0; i < routes.size(); i++) {
                routes.get(i).destination = destination;
                routes.get(i).index = i;
            }
            shownRoutes.clear();
            shownRoutes.addAll(routes);
            chosenRoute = 0;
            drawRoute();
            fitRoutes();
            callback.onRoutes(routes, 0);
        });
    }

    @Override
    public void estimate(Place destination, RouteCallback callback) {
        if (OsmAndOffline.installedFile() == null || !hasLocationPermission()) {
            callback.onRoutes(new ArrayList<>(), ROUTE_NO_DATA);
            return;
        }
        final double[] here = getPosition();
        final OsmAndOffline router = offline();
        if (here == null || router == null) {
            callback.onRoutes(new ArrayList<>(), ROUTE_NO_LOCATION);
            return;
        }
        // Only the best route: the card needs its time, not the alternatives.
        router.route(here[0], here[1], destination.latitude, destination.longitude, false, routes -> {
            if (destroyed) return;
            callback.onRoutes(routes, routes.isEmpty() ? ROUTE_NOT_FOUND : 0);
        });
    }

    @Override
    public void selectRoute(int index) {
        if (index < 0 || index >= shownRoutes.size()) return;
        chosenRoute = index;
        drawRoute();
    }

    @Override
    public void clearRoute() {
        shownRoutes.clear();
        chosenRoute = 0;
        drawRoute();
    }

    private static final String ROUTE_LABELS = "phogram-route-labels";
    private static final String ALTERNATIVE_LABELS = "phogram-route-alternatives-labels";

    /** A route's line with its color, its place in the list and its time, which the labels on the map show. */
    private static Feature lineOf(Route route) {
        final List<Point> points = new ArrayList<>(route.points.size());
        for (double[] point : route.points) {
            points.add(Point.fromLngLat(point[1], point[0]));
        }
        final Feature feature = Feature.fromGeometry(LineString.fromLngLats(points));
        feature.addStringProperty("color", String.format("#%06X", MapKinds.routeColor(route.index) & 0xFFFFFF));
        feature.addNumberProperty("index", route.index);
        feature.addStringProperty("label", MapsFormat.duration(route.seconds));
        return feature;
    }

    /**
     * Puts the shown routes on the style (again after a style change), or takes them off: each in its own color, as
     * on its card in the preview, the chosen one wide and on top, the others thinner and lighter, each with its time.
     */
    private void drawRoute() {
        if (style == null) return;
        final List<Feature> alternatives = new ArrayList<>();
        Feature chosen = null;
        for (int i = 0; i < shownRoutes.size(); i++) {
            if (i == chosenRoute) {
                chosen = lineOf(shownRoutes.get(i));
            } else {
                alternatives.add(lineOf(shownRoutes.get(i)));
            }
        }
        final FeatureCollection chosenLines = FeatureCollection.fromFeatures(chosen == null ? new Feature[0] : new Feature[] { chosen });
        final FeatureCollection alternativeLines = FeatureCollection.fromFeatures(alternatives);
        GeoJsonSource source = style.getSourceAs(ROUTE_SOURCE);
        GeoJsonSource alternativeSource = style.getSourceAs(ALTERNATIVE_SOURCE);
        if (source == null || alternativeSource == null) {
            alternativeSource = new GeoJsonSource(ALTERNATIVE_SOURCE, alternativeLines);
            source = new GeoJsonSource(ROUTE_SOURCE, chosenLines);
            style.addSource(alternativeSource);
            style.addSource(source);
            final Expression color = Expression.get("color");
            final LineLayer alternativeCasing = new LineLayer(ALTERNATIVE_CASING, ALTERNATIVE_SOURCE).withProperties(
                    PropertyFactory.lineColor(0xFFFFFFFF), PropertyFactory.lineWidth(8f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
            final LineLayer alternativeLine = new LineLayer(ALTERNATIVE_LINE, ALTERNATIVE_SOURCE).withProperties(
                    PropertyFactory.lineColor(color), PropertyFactory.lineWidth(5f), PropertyFactory.lineOpacity(0.6f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
            final LineLayer casing = new LineLayer(ROUTE_CASING, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(0xFFFFFFFF), PropertyFactory.lineWidth(10f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
            final LineLayer line = new LineLayer(ROUTE_LINE, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(color), PropertyFactory.lineWidth(6.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
            // Each route's time in the middle of its line, in its color on a white halo, as the bubbles of map apps.
            final SymbolLayer alternativeLabels = labels(ALTERNATIVE_LABELS, ALTERNATIVE_SOURCE, color);
            final SymbolLayer labels = labels(ROUTE_LABELS, ROUTE_SOURCE, color);
            if (style.getLayer("address_label") != null) {
                style.addLayerBelow(alternativeCasing, "address_label");
            } else {
                style.addLayer(alternativeCasing);
            }
            style.addLayerAbove(alternativeLine, ALTERNATIVE_CASING);
            style.addLayerAbove(casing, ALTERNATIVE_LINE);
            style.addLayerAbove(line, ROUTE_CASING);
            style.addLayer(alternativeLabels);
            style.addLayer(labels);
        } else {
            source.setGeoJson(chosenLines);
            alternativeSource.setGeoJson(alternativeLines);
        }
        final boolean navigating = navigator != null;
        final org.maplibre.android.style.layers.Layer chosenLabels = style.getLayer(ROUTE_LABELS);
        final org.maplibre.android.style.layers.Layer otherLabels = style.getLayer(ALTERNATIVE_LABELS);
        // While driving, the strip at the top tells the time; the labels would only cover the road.
        if (chosenLabels != null) chosenLabels.setProperties(PropertyFactory.visibility(navigating ? Property.NONE : Property.VISIBLE));
        if (otherLabels != null) otherLabels.setProperties(PropertyFactory.visibility(navigating ? Property.NONE : Property.VISIBLE));
    }

    private static SymbolLayer labels(String id, String source, Expression color) {
        return new SymbolLayer(id, source).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(new String[] { "Noto Sans Medium" }),
                PropertyFactory.textSize(14f),
                PropertyFactory.textColor(color),
                PropertyFactory.textHaloColor(0xFFFFFFFF),
                PropertyFactory.textHaloWidth(2.5f),
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
                PropertyFactory.textRotationAlignment(Property.TEXT_ROTATION_ALIGNMENT_VIEWPORT),
                PropertyFactory.textAllowOverlap(true));
    }

    /** A tap on a drawn route chooses it, as its card does. */
    private boolean onRouteClick(PointF screen) {
        if (tapListener == null || map == null || style == null || shownRoutes.size() < 2 || navigator != null) return false;
        final float slop = AndroidUtilities.dp(14);
        final List<Feature> features = map.queryRenderedFeatures(
                new android.graphics.RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop),
                ROUTE_LINE, ALTERNATIVE_LINE);
        for (Feature feature : features) {
            if (feature.hasProperty("index")) {
                final int index = feature.getNumberProperty("index").intValue();
                for (int i = 0; i < shownRoutes.size(); i++) {
                    if (shownRoutes.get(i).index == index) {
                        tapListener.onRouteTapped(i);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Frames all the shown routes, with room for the page's cards above and below. */
    private void fitRoutes() {
        if (map == null || shownRoutes.isEmpty()) return;
        final LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        int count = 0;
        for (Route route : shownRoutes) {
            for (double[] point : route.points) {
                bounds.include(new LatLng(point[0], point[1]));
                count++;
            }
        }
        if (count < 2) return;
        if (map.getLocationComponent().isLocationComponentActivated()) {
            map.getLocationComponent().setCameraMode(CameraMode.NONE);
        }
        final int side = AndroidUtilities.dp(40);
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), side, AndroidUtilities.dp(150), side,
                attributionBottom + AndroidUtilities.dp(250)), CAMERA_MS);
    }

    // Navigation: the camera follows the driver, tilted, turning with the road; each position is matched to the route,
    // and a driver who leaves it gets a new route from where they are.

    private static final double NAVIGATION_ZOOM = 16.5;
    private static final double NAVIGATION_TILT = 50;
    private RouteNavigator navigator;
    private NavigationListener navigationListener;
    private android.location.LocationListener navigationUpdates;
    private boolean rerouting;

    @Override
    @SuppressWarnings("MissingPermission")
    public void startNavigation(Route route, NavigationListener listener) {
        stopNavigation();
        navigator = new RouteNavigator(route);
        navigationListener = listener;
        shownRoutes.clear();
        shownRoutes.add(route);
        chosenRoute = 0;
        drawRoute();
        activateLocation();
        if (map != null && map.getLocationComponent().isLocationComponentActivated()) {
            final LocationComponent location = map.getLocationComponent();
            location.setRenderMode(RenderMode.GPS);
            // One transition to the driver, zoomed in and tilted; zooming and tilting separately while the camera
            // is still moving into tracking is ignored by MapLibre.
            location.setCameraMode(CameraMode.TRACKING_GPS, CAMERA_MS, NAVIGATION_ZOOM, null, NAVIGATION_TILT, null);
        }
        final android.location.LocationManager manager =
                (android.location.LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null || !hasLocationPermission()) return;
        navigationUpdates = new PositionListener() {
            @Override
            public void onLocationChanged(Location location) {
                AndroidUtilities.runOnUIThread(() -> onNavigationPosition(location));
            }
        };
        try {
            manager.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 0, navigationUpdates, android.os.Looper.getMainLooper());
        } catch (Exception e) {
            FileLog.e(e);
        }
        final double[] here = getPosition();
        if (here != null) {
            final Location start = new Location("phogram");
            start.setLatitude(here[0]);
            start.setLongitude(here[1]);
            onNavigationPosition(start);
        }
    }

    private void onNavigationPosition(Location location) {
        final RouteNavigator current = navigator;
        if (current == null || destroyed || location == null) return;
        // The arrow and the camera follow the positions navigation uses, not only those of MapLibre's own provider.
        if (map != null && map.getLocationComponent().isLocationComponentActivated() && !"phogram".equals(location.getProvider())) {
            map.getLocationComponent().forceLocationUpdate(location);
        }
        final NavigationState state = current.update(location.getLatitude(), location.getLongitude());
        if (current.isOffRoute() && !rerouting && current.route.destination != null) {
            // The driver left the route: a new one from here, drawn as soon as it is found.
            rerouting = true;
            final Place destination = current.route.destination;
            final OsmAndOffline router = offline();
            if (router != null) {
                router.route(location.getLatitude(), location.getLongitude(), destination.latitude, destination.longitude, false, routes -> {
                    rerouting = false;
                    if (navigator == null || destroyed || routes.isEmpty()) return;
                    final Route route = routes.get(0);
                    route.destination = destination;
                    route.index = current.route.index;
                    navigator = new RouteNavigator(route);
                    shownRoutes.clear();
                    shownRoutes.add(route);
                    chosenRoute = 0;
                    drawRoute();
                });
            } else {
                rerouting = false;
            }
        }
        state.rerouting = rerouting;
        if (navigationListener != null) {
            navigationListener.onNavigation(state);
        }
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void stopNavigation() {
        if (navigationUpdates != null) {
            final android.location.LocationManager manager =
                    (android.location.LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            try {
                if (manager != null) manager.removeUpdates(navigationUpdates);
            } catch (Exception e) {
                FileLog.e(e);
            }
            navigationUpdates = null;
        }
        if (navigator == null) return;
        navigator = null;
        navigationListener = null;
        rerouting = false;
        if (map != null && map.getLocationComponent().isLocationComponentActivated()) {
            final LocationComponent location = map.getLocationComponent();
            location.setCameraMode(CameraMode.NONE);
            location.setRenderMode(RenderMode.NORMAL);
            map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(map.getCameraPosition())
                    .tilt(0).bearing(0).build()), CAMERA_MS);
        }
    }

    @Override
    public void search(String query, SearchCallback callback) {
        // From the user's last position, so the results go from the nearest to the farthest; the map's center
        // when the position is not known.
        final double[] here = getPosition();
        final LatLng center = here != null ? new LatLng(here[0], here[1])
                : map != null ? map.getCameraPosition().target : new LatLng(START_LATITUDE, START_LONGITUDE);
        final OsmAndOffline search = offline();
        if (search != null) {
            search.search(query, center.getLatitude(), center.getLongitude(), callback);
        }
    }

    // The shown place: the camera flies to it, and every camera move reports where it is so the page keeps its pin on it.

    @Override
    public void showPlace(Place place, PinListener listener) {
        shownPlace = place;
        pinListener = listener;
        if (place == null || map == null) return;
        // Stops following the device's position, or it would pull the camera away from the place.
        if (map.getLocationComponent().isLocationComponentActivated()) {
            map.getLocationComponent().setCameraMode(CameraMode.NONE);
        }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(place.latitude, place.longitude),
                Math.max(map.getCameraPosition().zoom, PLACE_ZOOM)), CAMERA_MS);
        reportPin();
    }

    private void onCameraMoved() {
        reportPin();
        if (cameraListener != null) {
            cameraListener.run();
        }
    }

    private void onCameraIdle() {
        reportPin();
        if (cameraListener != null) {
            cameraListener.run();
        }
        if (map == null || destroyed) return;
        if (offline != null) {
            final CameraPosition camera = map.getCameraPosition();
            offline.setCenter(camera.target.getLatitude(), camera.target.getLongitude(), camera.zoom);
        } else {
            AndroidUtilities.cancelRunOnUIThread(startOffline);
            AndroidUtilities.runOnUIThread(startOffline, OFFLINE_START_DELAY_MS);
        }
    }

    private void reportPin() {
        final Place place = shownPlace;
        if (place == null || pinListener == null || map == null) return;
        final PointF point = map.getProjection().toScreenLocation(new LatLng(place.latitude, place.longitude));
        pinListener.onPinMoved(point.x, point.y);
    }

    // Lifecycle: MapLibre's view follows the page.

    @Override
    @SuppressWarnings("MissingPermission")
    public void onResume() {
        resumed = true;
        if (!started) {
            started = true;
            view.onStart();
        }
        view.onResume();
        if (map != null && map.getLocationComponent().isLocationComponentActivated()) {
            map.getLocationComponent().setLocationComponentEnabled(true);
        }
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void onPause() {
        resumed = false;
        // The position is not followed while another tab is open, so the GPS does not use the battery.
        if (map != null && map.getLocationComponent().isLocationComponentActivated()) {
            map.getLocationComponent().setLocationComponentEnabled(false);
        }
        view.onPause();
    }

    @Override
    public void onDestroy() {
        stopNavigation();
        destroyed = true;
        shownPlace = null;
        AndroidUtilities.cancelRunOnUIThread(startOffline);
        if (offline != null) {
            offline.destroy();
        }
        MapData.removeListener(mapDataChanged);
        if (started) {
            view.onStop();
        }
        view.onDestroy();
    }
}
