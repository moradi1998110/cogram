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
            addRoadImages(loaded);
            activateLocation();
            drawRoute();
            drawRoadMarks();
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
                addRoadImages(loaded);
                activateLocation();
                drawRoute();
                drawRoadMarks();
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
        if (onAlongClick(screen)) return true;
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

    // On the road while navigating: the next turn drawn as a white arrow on the route, the names of the coming roads
    // in Telegram-blue plates on the line, the warnings as road signs, and places of a chosen kind as pins.

    private static final String MANEUVER_SOURCE = "phogram-maneuver";
    private static final String MANEUVER_LINE = "phogram-maneuver-line";
    private static final String MANEUVER_CASING = "phogram-maneuver-casing";
    private static final String MANEUVER_HEAD_SOURCE = "phogram-maneuver-head";
    private static final String MANEUVER_HEAD = "phogram-maneuver-head-layer";
    private static final String NAMES_SOURCE = "phogram-names";
    private static final String NAMES_LAYER = "phogram-names-layer";
    private static final String WARNINGS_SOURCE = "phogram-warnings";
    private static final String WARNINGS_LAYER = "phogram-warnings-layer";
    private static final String ALONG_SOURCE = "phogram-along";
    private static final String ALONG_LAYER = "phogram-along-layer";
    private static final String ALONG_NAMES_LAYER = "phogram-along-names";
    /** The user moved the map away from the driver: every road's name on the route pops up, not only the next one. */
    private boolean freeCamera;
    /** Where along the route the places on the way were looked for, so their distance ahead stays right. */
    private float alongBase;
    private String nearKey = "";
    private int shownManeuver = -2;
    private final List<AlongPlace> alongPlaces = new ArrayList<>();
    private Runnable trackingListener;
    private boolean trackingListenerAdded;

    /** The road images, made once per style. */
    private void addRoadImages(Style target) {
        final int plate = MapKinds.dp(28);
        final int corner = MapKinds.dp(9);
        final List<org.maplibre.android.maps.ImageStretches> stretch = new ArrayList<>();
        stretch.add(new org.maplibre.android.maps.ImageStretches(corner, plate - corner));
        target.addImage("phogram-plate", MapKinds.namePlate(), stretch, stretch,
                new org.maplibre.android.maps.ImageContent(corner, MapKinds.dp(5), plate - corner, plate - MapKinds.dp(5)));
        target.addImage("phogram-arrow-head", MapKinds.arrowHead());
        // Telegram's message bubble, stretched in its middle around a name; its corners and tail keep their shape.
        final int tail = MapKinds.dp(MapKinds.BUBBLE_TAIL);
        final int radius = MapKinds.dp(MapKinds.BUBBLE_RADIUS);
        final int bubble = MapKinds.dp(MapKinds.BUBBLE_SIZE);
        final List<org.maplibre.android.maps.ImageStretches> bubbleX = new ArrayList<>();
        bubbleX.add(new org.maplibre.android.maps.ImageStretches(tail + radius, tail + bubble - radius));
        final List<org.maplibre.android.maps.ImageStretches> bubbleY = new ArrayList<>();
        bubbleY.add(new org.maplibre.android.maps.ImageStretches(radius, bubble - radius));
        target.addImage("phogram-bubble", MapKinds.bubbleImage(), bubbleX, bubbleY,
                new org.maplibre.android.maps.ImageContent(tail + MapKinds.dp(10), MapKinds.dp(7), tail + bubble - MapKinds.dp(10), bubble - MapKinds.dp(7)));
        for (int type = Warning.TRAFFIC_LIGHTS; type <= Warning.PEDESTRIANS; type++) {
            target.addImage("phogram-warning-" + type, MapKinds.warningImage(type));
        }
        for (int kind = KIND_OTHER; kind <= KIND_PIN; kind++) {
            target.addImage("phogram-pin-" + kind, MapKinds.pinImage(kind));
        }
    }

    /** The road layers on top of the routes, made once per style. */
    private void ensureRoadLayers() {
        if (style == null || style.getSource(MANEUVER_SOURCE) != null || style.getLayer(ROUTE_LINE) == null) return;
        final FeatureCollection empty = FeatureCollection.fromFeatures(new Feature[0]);
        style.addSource(new GeoJsonSource(MANEUVER_SOURCE, empty));
        style.addSource(new GeoJsonSource(MANEUVER_HEAD_SOURCE, empty));
        style.addSource(new GeoJsonSource(NAMES_SOURCE, empty));
        style.addSource(new GeoJsonSource(WARNINGS_SOURCE, empty));
        style.addSource(new GeoJsonSource(ALONG_SOURCE, empty));
        final LineLayer casing = new LineLayer(MANEUVER_CASING, MANEUVER_SOURCE).withProperties(
                PropertyFactory.lineColor(0xFF1F4E8C), PropertyFactory.lineWidth(10f),
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
        final LineLayer line = new LineLayer(MANEUVER_LINE, MANEUVER_SOURCE).withProperties(
                PropertyFactory.lineColor(0xFFFFFFFF), PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
        final SymbolLayer head = new SymbolLayer(MANEUVER_HEAD, MANEUVER_HEAD_SOURCE).withProperties(
                PropertyFactory.iconImage("phogram-arrow-head"),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true));
        final SymbolLayer names = new SymbolLayer(NAMES_LAYER, NAMES_SOURCE).withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(new String[] { "Noto Sans Medium" }),
                PropertyFactory.textSize(14f),
                PropertyFactory.textColor(Theme.getColor(Theme.key_chat_messageTextIn)),
                // In Telegram's message bubble, its tail on the road: the name sits up and to the right of the point.
                PropertyFactory.iconImage("phogram-bubble"),
                PropertyFactory.iconTextFit(Property.ICON_TEXT_FIT_BOTH),
                PropertyFactory.iconTextFitPadding(new Float[] { 2f, 3f, 2f, 3f }),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM_LEFT),
                PropertyFactory.textOffset(new Float[] { 1.2f, -0.9f }),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.symbolSortKey(Expression.get("order")));
        // A place's name over its pin, in the same bubble, once the driver is near it.
        final SymbolLayer alongNames = new SymbolLayer(ALONG_NAMES_LAYER, ALONG_SOURCE).withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(new String[] { "Noto Sans Medium" }),
                PropertyFactory.textSize(13f),
                PropertyFactory.textColor(Theme.getColor(Theme.key_chat_messageTextIn)),
                PropertyFactory.iconImage("phogram-bubble"),
                PropertyFactory.iconTextFit(Property.ICON_TEXT_FIT_BOTH),
                PropertyFactory.iconTextFitPadding(new Float[] { 2f, 3f, 2f, 3f }),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM_LEFT),
                PropertyFactory.textOffset(new Float[] { 1.2f, -4.4f }),
                PropertyFactory.textMaxWidth(10f),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.iconAllowOverlap(true));
        alongNames.setFilter(Expression.eq(Expression.get("near"), Expression.literal(true)));
        final SymbolLayer warnings = new SymbolLayer(WARNINGS_LAYER, WARNINGS_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(false),
                // Far out only the important signs fit; close in, all of them.
                PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(10, 0.6f), Expression.stop(16, 1f))));
        final SymbolLayer along = new SymbolLayer(ALONG_LAYER, ALONG_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(false),
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(new String[] { "Noto Sans Medium" }),
                PropertyFactory.textSize(12f),
                PropertyFactory.textColor(0xFF1A1A1A),
                PropertyFactory.textHaloColor(0xFFFFFFFF),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOptional(true),
                PropertyFactory.symbolSortKey(Expression.get("order")));
        style.addLayerAbove(casing, ROUTE_LINE);
        style.addLayerAbove(line, MANEUVER_CASING);
        style.addLayer(warnings);
        style.addLayer(along);
        style.addLayer(alongNames);
        style.addLayer(names);
        style.addLayer(head);
    }

    /** Puts the navigation's road marks on the style again after a style change, or clears them without navigation. */
    private void drawRoadMarks() {
        ensureRoadLayers();
        if (style == null || style.getSource(MANEUVER_SOURCE) == null) return;
        shownManeuver = -2;
        final RouteNavigator current = navigator;
        final List<Feature> warnings = new ArrayList<>();
        if (current != null) {
            for (Warning warning : current.route.warnings) {
                final Feature feature = Feature.fromGeometry(Point.fromLngLat(warning.longitude, warning.latitude));
                feature.addStringProperty("icon", "phogram-warning-" + warning.type);
                warnings.add(feature);
            }
        } else {
            setSource(MANEUVER_SOURCE, FeatureCollection.fromFeatures(new Feature[0]));
            setSource(MANEUVER_HEAD_SOURCE, FeatureCollection.fromFeatures(new Feature[0]));
            setSource(NAMES_SOURCE, FeatureCollection.fromFeatures(new Feature[0]));
            alongPlaces.clear();
        }
        setSource(WARNINGS_SOURCE, FeatureCollection.fromFeatures(warnings));
        drawAlong();
    }

    private void setSource(String id, FeatureCollection features) {
        if (style == null) return;
        final GeoJsonSource source = style.getSourceAs(id);
        if (source != null) source.setGeoJson(features);
    }

    /**
     * The next turn as an arrow on the road (the last 35 m before it and the first 25 m after it, with a head), and the
     * names of the next two roads with a name, at the turns onto them. Redrawn only when the next turn changes.
     */
    private void drawManeuver(NavigationState state) {
        if (style == null || style.getSource(MANEUVER_SOURCE) == null) return;
        final Maneuver next = state.next;
        final int key = next == null ? -1 : next.pointIndex;
        if (key == shownManeuver) return;
        shownManeuver = key;
        final Route route = state.route;
        if (next == null || next.type == Maneuver.ARRIVE || route == null) {
            setSource(MANEUVER_SOURCE, FeatureCollection.fromFeatures(new Feature[0]));
            setSource(MANEUVER_HEAD_SOURCE, FeatureCollection.fromFeatures(new Feature[0]));
        } else {
            final float from = next.distance - 35;
            final float to = next.distance + 25;
            final List<Point> points = new ArrayList<>();
            for (int i = 0; i < route.points.size(); i++) {
                if (route.along[i] >= from && route.along[i] <= to) {
                    points.add(Point.fromLngLat(route.points.get(i)[1], route.points.get(i)[0]));
                }
            }
            if (points.size() >= 2) {
                setSource(MANEUVER_SOURCE, FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points))));
                final Point end = points.get(points.size() - 1);
                final Point before = points.get(points.size() - 2);
                final Feature head = Feature.fromGeometry(end);
                head.addNumberProperty("bearing", bearing(before, end));
                setSource(MANEUVER_HEAD_SOURCE, FeatureCollection.fromFeature(head));
            }
        }
        lastNext = next;
        drawNames();
    }

    private Maneuver lastNext;

    /**
     * Road names on the route in Telegram's bubbles: while the camera follows the driver, only the next road's; once
     * the user moves the map, every named road of the route pops up (each once, at its middle, where it is long
     * enough for a name), and the map's collisions keep only those that fit.
     */
    private void drawNames() {
        if (style == null || style.getSource(NAMES_SOURCE) == null) return;
        final RouteNavigator current = navigator;
        final List<Feature> names = new ArrayList<>();
        if (current != null) {
            final Route route = current.route;
            if (freeCamera) {
                String previous = "";
                for (int r = 0; r < route.roadStarts.length; r++) {
                    final String road = route.roadNames[r] == null ? "" : route.roadNames[r];
                    final int start = route.roadStarts[r];
                    final int end = r + 1 < route.roadStarts.length ? route.roadStarts[r + 1] : route.points.size() - 1;
                    if (road.isEmpty() || road.equals(previous) || route.along[end] - route.along[start] < 120) continue;
                    previous = road;
                    final int middle = Math.min((start + end) / 2, route.points.size() - 1);
                    final Feature name = Feature.fromGeometry(Point.fromLngLat(route.points.get(middle)[1], route.points.get(middle)[0]));
                    name.addStringProperty("name", road);
                    name.addNumberProperty("order", names.size());
                    names.add(name);
                }
            } else if (lastNext != null) {
                for (Maneuver maneuver : route.maneuvers) {
                    if (maneuver.distance < lastNext.distance || maneuver.street.isEmpty()) continue;
                    final double[] at = route.points.get(Math.min(maneuver.pointIndex + 1, route.points.size() - 1));
                    final Feature name = Feature.fromGeometry(Point.fromLngLat(at[1], at[0]));
                    name.addStringProperty("name", maneuver.street);
                    name.addNumberProperty("order", 0);
                    names.add(name);
                    break;
                }
            }
        }
        setSource(NAMES_SOURCE, FeatureCollection.fromFeatures(names));
    }

    private static double bearing(Point from, Point to) {
        final double lat1 = Math.toRadians(from.latitude());
        final double lat2 = Math.toRadians(to.latitude());
        final double dLon = Math.toRadians(to.longitude() - from.longitude());
        final double y = Math.sin(dLon) * Math.cos(lat2);
        final double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** The driver's distance along the route at the last position. */
    private float travelled;

    /** A place on the way within a kilometer ahead (or just passed): its name shows over its pin. */
    private boolean near(AlongPlace along) {
        final float ahead = alongBase + along.ahead - travelled;
        return ahead > -50 && ahead < 1000;
    }

    /** Redraws the places only when one comes near or leaves, not on every position. */
    private void updateNearPlaces() {
        if (alongPlaces.isEmpty()) return;
        final StringBuilder key = new StringBuilder();
        for (int i = 0; i < alongPlaces.size(); i++) {
            if (near(alongPlaces.get(i))) key.append(i).append(',');
        }
        if (!key.toString().equals(nearKey)) {
            nearKey = key.toString();
            drawAlong();
        }
    }

    private void drawAlong() {
        final List<Feature> features = new ArrayList<>();
        int order = 0;
        for (AlongPlace along : alongPlaces) {
            final Feature feature = Feature.fromGeometry(Point.fromLngLat(along.place.longitude, along.place.latitude));
            feature.addStringProperty("icon", "phogram-pin-" + along.place.kind);
            feature.addStringProperty("label", MapsFormat.distance(along.ahead));
            feature.addStringProperty("name", along.place.name);
            feature.addStringProperty("type", along.place.type);
            feature.addNumberProperty("kind", along.place.kind);
            feature.addBooleanProperty("near", near(along));
            feature.addNumberProperty("order", order++);
            features.add(feature);
        }
        setSource(ALONG_SOURCE, FeatureCollection.fromFeatures(features));
    }

    @Override
    public void showAlongRoute(int kind, AlongCallback callback) {
        final RouteNavigator current = navigator;
        final OsmAndOffline router = offline();
        if (kind == KIND_OTHER || current == null || router == null) {
            alongPlaces.clear();
            drawAlong();
            callback.onPlaces(new ArrayList<>());
            return;
        }
        final float base = current.route.along[Math.min(current.pointIndex(), current.route.along.length - 1)];
        router.alongRoute(current.route, current.pointIndex(), kind, places -> {
            if (destroyed || navigator == null) return;
            alongBase = base;
            nearKey = "";
            alongPlaces.clear();
            alongPlaces.addAll(places);
            drawAlong();
            callback.onPlaces(places);
        });
    }

    /** A tap on a place pin along the route offers it as a stop. */
    private boolean onAlongClick(PointF screen) {
        if (tapListener == null || map == null || style == null || alongPlaces.isEmpty()) return false;
        final float slop = AndroidUtilities.dp(16);
        final List<Feature> features = map.queryRenderedFeatures(
                new android.graphics.RectF(screen.x - slop, screen.y - slop * 2, screen.x + slop, screen.y + slop), ALONG_LAYER);
        for (Feature feature : features) {
            if (!(feature.geometry() instanceof Point)) continue;
            final Point at = (Point) feature.geometry();
            final String name = feature.hasProperty("name") ? feature.getStringProperty("name") : "";
            final String type = feature.hasProperty("type") ? feature.getStringProperty("type") : "";
            final int kind = feature.hasProperty("kind") ? feature.getNumberProperty("kind").intValue() : KIND_OTHER;
            tapListener.onAlongTapped(new Place(name, "", at.latitude(), at.longitude(), kind, type));
            return true;
        }
        return false;
    }

    @Override
    public void addStop(Place stop) {
        final RouteNavigator current = navigator;
        final double[] here = getPosition();
        final OsmAndOffline router = offline();
        if (current == null || here == null || router == null || current.route.destination == null) return;
        final List<Place> stops = new ArrayList<>(current.route.stops);
        stops.add(stop);
        final Place destination = current.route.destination;
        final int index = current.route.index;
        rerouting = true;
        router.route(here[0], here[1], destination.latitude, destination.longitude, false, stops, routes -> {
            rerouting = false;
            if (navigator == null || destroyed || routes.isEmpty()) return;
            final Route route = routes.get(0);
            route.destination = destination;
            route.index = index;
            route.stops = stops;
            navigator = new RouteNavigator(route);
            shownRoutes.clear();
            shownRoutes.add(route);
            chosenRoute = 0;
            alongPlaces.clear();
            drawRoute();
            drawRoadMarks();
        });
    }

    @Override
    public void showOverview() {
        final RouteNavigator current = navigator;
        if (map == null || current == null) return;
        freeCamera = true;
        drawNames();
        if (map.getLocationComponent().isLocationComponentActivated()) {
            map.getLocationComponent().setCameraMode(CameraMode.NONE);
        }
        final LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        final int from = current.pointIndex();
        for (int i = from; i < current.route.points.size(); i++) {
            bounds.include(new LatLng(current.route.points.get(i)[0], current.route.points.get(i)[1]));
        }
        bounds.include(new LatLng(current.route.points.get(current.route.points.size() - 1)[0], current.route.points.get(current.route.points.size() - 1)[1]));
        if (from >= current.route.points.size() - 1) return;
        final int side = AndroidUtilities.dp(48);
        map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).tilt(0).bearing(0).build()), 1);
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), side, AndroidUtilities.dp(170), side,
                attributionBottom + AndroidUtilities.dp(120)), CAMERA_MS);
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void recenter() {
        if (map == null || navigator == null || !map.getLocationComponent().isLocationComponentActivated()) return;
        freeCamera = false;
        drawNames();
        map.getLocationComponent().setCameraMode(CameraMode.TRACKING_GPS, CAMERA_MS, NAVIGATION_ZOOM, null, NAVIGATION_TILT, null);
    }

    @Override
    public void setTrackingListener(Runnable onDismissed) {
        trackingListener = onDismissed;
    }

    /** Tells the page when the user moves the map away from the driver, so it offers to re-center. */
    private void listenToTracking() {
        if (trackingListenerAdded || map == null || !map.getLocationComponent().isLocationComponentActivated()) return;
        trackingListenerAdded = true;
        map.getLocationComponent().addOnCameraTrackingChangedListener(new org.maplibre.android.location.OnCameraTrackingChangedListener() {
            @Override
            public void onCameraTrackingDismissed() {
                if (navigator != null) {
                    freeCamera = true;
                    drawNames();
                }
                if (navigator != null && trackingListener != null) {
                    trackingListener.run();
                }
            }

            @Override
            public void onCameraTrackingChanged(int currentMode) {
            }
        });
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
        freeCamera = false;
        navigator = new RouteNavigator(route);
        navigationListener = listener;
        shownRoutes.clear();
        shownRoutes.add(route);
        chosenRoute = 0;
        drawRoute();
        drawRoadMarks();
        activateLocation();
        listenToTracking();
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
        state.speed = location.hasSpeed() ? location.getSpeed() * 3.6f : 0;
        // A stop reached leaves the route's stops, so a new route after a wrong turn does not go back to it.
        if (state.passedStop != null) {
            current.route.stops.remove(state.passedStop);
        }
        drawManeuver(state);
        travelled = state.route != null && state.route.along.length > 0 ? state.route.along[state.route.along.length - 1] - state.remainingMeters : 0;
        updateNearPlaces();
        if (current.isOffRoute() && !rerouting && current.route.destination != null) {
            // The driver left the route: a new one from here, through the stops still ahead, drawn once found.
            rerouting = true;
            final Place destination = current.route.destination;
            final List<Place> stops = new ArrayList<>(current.route.stops);
            final OsmAndOffline router = offline();
            if (router != null) {
                router.route(location.getLatitude(), location.getLongitude(), destination.latitude, destination.longitude, false, stops, routes -> {
                    rerouting = false;
                    if (navigator == null || destroyed || routes.isEmpty()) return;
                    final Route route = routes.get(0);
                    route.destination = destination;
                    route.index = current.route.index;
                    route.stops = stops;
                    navigator = new RouteNavigator(route);
                    shownRoutes.clear();
                    shownRoutes.add(route);
                    chosenRoute = 0;
                    alongPlaces.clear();
                    drawRoute();
                    drawRoadMarks();
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
        drawRoadMarks();
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
