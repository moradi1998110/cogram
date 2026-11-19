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

    /** The region's tiles, which ship in the app's assets during the prototype and are copied out once. */
    private static final String TILES_ASSET = "phogram_map/iraq.pmtiles";
    private static final String TILES_FILE = "maps/iraq.pmtiles";

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
    private int[] padding = new int[4];

    private Place shownPlace;
    private PinListener pinListener;

    MapLibreMapEngine(Context context) {
        this.context = context;
        MapLibre.getInstance(context.getApplicationContext());
        view = new MapView(context);
        view.onCreate(null);
        offline = new OsmAndOffline(context);
        prepareTiles(file -> {
            if (destroyed) return;
            view.getMapAsync(map -> onMap(map, file));
        });
    }

    /** Copies the tiles out of the app's assets on first use, off the main thread: MapLibre reads them by range. */
    private void prepareTiles(Utilities.Callback<File> done) {
        final File file = new File(context.getFilesDir(), TILES_FILE);
        if (file.exists() && file.length() > 0) {
            done.run(file);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            final File temp = new File(file.getPath() + ".tmp");
            try {
                file.getParentFile().mkdirs();
                try (InputStream in = context.getAssets().open(TILES_ASSET); OutputStream out = new FileOutputStream(temp)) {
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
        map.setMaxZoomPreference(19);
        map.setCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(START_LATITUDE, START_LONGITUDE)).zoom(START_ZOOM).build());
        map.addOnCameraMoveListener(this::onCameraMoved);
        map.addOnCameraIdleListener(this::onCameraIdle);
        map.setStyle(new Style.Builder().fromJson(styleJson(tiles)), loaded -> {
            if (destroyed) return;
            style = loaded;
            applyPadding();
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

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        padding = new int[] { left, top, right, bottom };
        applyPadding();
    }

    private void applyPadding() {
        if (map != null) {
            map.moveCamera(CameraUpdateFactory.paddingTo(padding[0], padding[1], padding[2], padding[3]));
        }
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void moveToMyLocation() {
        activateLocation();
        if (map == null || !map.getLocationComponent().isLocationComponentActivated()) return;
        final LocationComponent location = map.getLocationComponent();
        final Location last = location.getLastKnownLocation();
        if (last != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(last.getLatitude(), last.getLongitude()),
                    Math.max(map.getCameraPosition().zoom, MY_LOCATION_ZOOM)), CAMERA_MS);
        }
        // Follows the position from here on, until the user moves the map, as Telegram's location page does.
        location.setCameraMode(CameraMode.TRACKING);
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
        offline.setDownloadListener(listener);
    }

    @Override
    public Download getOfferedDownload() {
        return offline.getOfferedDownload();
    }

    @Override
    public void startOfferedDownload() {
        offline.startOfferedDownload();
    }

    @Override
    public void cancelOfferedDownload() {
        offline.cancelOfferedDownload();
    }

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
        if (started) {
            view.onStop();
        }
        view.onDestroy();
    }
}
