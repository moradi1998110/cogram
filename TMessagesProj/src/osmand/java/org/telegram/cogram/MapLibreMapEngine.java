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
import org.maplibre.android.style.layers.LineLayer;
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
            tapListener.onPlaceTapped(new Place(name,
                    String.format(java.util.Locale.US, "%.5f, %.5f", at.latitude(), at.longitude()), at.latitude(), at.longitude()));
            return true;
        }
        return false;
    }

    // Routes: OsmAnd's offline car router from the device's position, drawn as a line under the labels.

    private static final String ROUTE_SOURCE = "phogram-route";
    private static final String ROUTE_CASING = "phogram-route-casing";
    private static final String ROUTE_LINE = "phogram-route-line";
    private Route shownRoute;

    @Override
    public boolean hasSearchData() {
        return OsmAndOffline.installedFile() != null;
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void showRoute(Place destination, RouteCallback callback) {
        if (OsmAndOffline.installedFile() == null) {
            callback.onRoute(null, ROUTE_NO_DATA);
            return;
        }
        Location here = null;
        if (map != null && map.getLocationComponent().isLocationComponentActivated()) {
            here = map.getLocationComponent().getLastKnownLocation();
        }
        if (here != null) {
            routeFrom(here, destination, callback);
            return;
        }
        if (!hasLocationPermission()) {
            callback.onRoute(null, ROUTE_NO_LOCATION);
            return;
        }
        org.telegram.messenger.ApplicationLoader.getLocationServiceProvider().getLastLocation(fix -> AndroidUtilities.runOnUIThread(() -> {
            if (destroyed) return;
            if (fix != null) {
                routeFrom(fix, destination, callback);
                return;
            }
            // No position kept yet (after a restart, or with the GPS just turned on): the GPS is asked for a new one.
            currentLocation(fresh -> {
                if (destroyed) return;
                if (fresh == null) {
                    callback.onRoute(null, ROUTE_NO_LOCATION);
                } else {
                    routeFrom(fresh, destination, callback);
                }
            });
        }));
    }

    private static final long FRESH_LOCATION_TIMEOUT_MS = 20_000;

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
        // A full listener, not a lambda: before Android 10 the system also calls the status methods, which have no
        // default bodies there.
        listeners[0] = new android.location.LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (finished[0]) return;
                    AndroidUtilities.cancelRunOnUIThread(timeout);
                    finish.run();
                    done.run(location);
                });
            }

            @Override
            public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
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

    private void routeFrom(Location here, Place destination, RouteCallback callback) {
        final OsmAndOffline router = offline();
        if (router == null) {
            callback.onRoute(null, ROUTE_NOT_FOUND);
            return;
        }
        router.route(here.getLatitude(), here.getLongitude(), destination.latitude, destination.longitude, route -> {
            if (destroyed) return;
            if (route == null || route.points.size() < 2) {
                callback.onRoute(null, ROUTE_NOT_FOUND);
                return;
            }
            shownRoute = route;
            drawRoute();
            fitRoute(route);
            callback.onRoute(route, 0);
        });
    }

    @Override
    public void clearRoute() {
        shownRoute = null;
        drawRoute();
    }

    /** Puts the shown route on the style (again after a style change), or takes it off. */
    private void drawRoute() {
        if (style == null) return;
        final Route route = shownRoute;
        GeoJsonSource source = style.getSourceAs(ROUTE_SOURCE);
        if (route == null) {
            if (source != null) {
                source.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));
            }
            return;
        }
        final List<Point> points = new ArrayList<>(route.points.size());
        for (double[] point : route.points) {
            points.add(Point.fromLngLat(point[1], point[0]));
        }
        final Feature line = Feature.fromGeometry(LineString.fromLngLats(points));
        if (source == null) {
            source = new GeoJsonSource(ROUTE_SOURCE, line);
            style.addSource(source);
            // Telegram's blue over a white casing, drawn below the first label layer so names stay readable.
            final LineLayer casing = new LineLayer(ROUTE_CASING, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(0xFFFFFFFF), PropertyFactory.lineWidth(9f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
            final LineLayer blue = new LineLayer(ROUTE_LINE, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(0xFF3A8EE6), PropertyFactory.lineWidth(5.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
            if (style.getLayer("address_label") != null) {
                style.addLayerBelow(casing, "address_label");
            } else {
                style.addLayer(casing);
            }
            style.addLayerAbove(blue, ROUTE_CASING);
        } else {
            source.setGeoJson(line);
        }
    }

    private void fitRoute(Route route) {
        if (map == null || route.points.size() < 2) return;
        final LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (double[] point : route.points) {
            bounds.include(new LatLng(point[0], point[1]));
        }
        if (map.getLocationComponent().isLocationComponentActivated()) {
            map.getLocationComponent().setCameraMode(CameraMode.NONE);
        }
        // Room for the page's floating buttons on the sides and the place's card at the bottom.
        final int side = AndroidUtilities.dp(56);
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), side, AndroidUtilities.dp(96), side,
                attributionBottom + AndroidUtilities.dp(140)), CAMERA_MS);
    }

    @Override
    public void search(String query, SearchCallback callback) {
        final LatLng center = map != null ? map.getCameraPosition().target : new LatLng(START_LATITUDE, START_LONGITUDE);
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
