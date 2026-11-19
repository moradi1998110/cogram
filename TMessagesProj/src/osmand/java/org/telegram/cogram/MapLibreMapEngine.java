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
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
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

    private final Context context;
    private final MapView view;
    private final OsmAndOffline offline;
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
        view = new MapView(context);
        view.onCreate(null);
        offline = new OsmAndOffline(context);
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
        map.addOnCameraMoveListener(this::onCameraMoved);
        map.addOnCameraIdleListener(this::onCameraIdle);
        // MapLibre draws on its own GPU surface, which Android's frame statistics do not see, so it reports its
        // frame rate itself; the emulator check reads these lines to compare the map's smoothness.
        map.setOnFpsChangedListener(fps -> android.util.Log.d("PhogramMapFps", String.format(java.util.Locale.US, "%.1f", fps)));
        map.setStyle(new Style.Builder().fromJson(styleJson(tiles)), loaded -> {
            if (destroyed) return;
            style = loaded;
            activateLocation();
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

    @Override
    public void setDownloadListener(Runnable listener) {
        downloadListener = listener;
        offline.setDownloadListener(listener);
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
        return offline.getOfferedDownload();
    }

    @Override
    public void startOfferedDownload() {
        final MapData.RegionFile tiles = MapData.offer();
        if (tiles != null) {
            MapData.download(tiles);
        } else {
            offline.startOfferedDownload();
        }
    }

    @Override
    public void cancelOfferedDownload() {
        if (MapData.offer() != null) {
            MapData.cancel();
        } else {
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
            });
        }
        if (downloadListener != null) {
            downloadListener.run();
        }
    };

    @Override
    public void search(String query, SearchCallback callback) {
        final LatLng center = map != null ? map.getCameraPosition().target : new LatLng(START_LATITUDE, START_LONGITUDE);
        offline.search(query, center.getLatitude(), center.getLongitude(), callback);
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
    }

    private void onCameraIdle() {
        reportPin();
        if (map != null) {
            final CameraPosition camera = map.getCameraPosition();
            offline.setCenter(camera.target.getLatitude(), camera.target.getLongitude(), camera.zoom);
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
        offline.destroy();
        MapData.removeListener(mapDataChanged);
        if (started) {
            view.onStop();
        }
        view.onDestroy();
    }
}
