package org.telegram.cogram;

import android.content.Context;
import android.view.View;

import net.osmand.plus.AppInitializeListener;
import net.osmand.plus.AppInitializer;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.enums.CompassMode;
import net.osmand.plus.views.MapViewWithLayers;
import net.osmand.plus.views.OsmandMapTileView;

import org.telegram.messenger.FileLog;

import eu.siacs.conversations.PhogramBaseApplication;

/**
 * OsmAnd's map in Phogram's Maps tab, without OsmAnd's own screens. OsmAnd keeps one map view for the whole
 * app; this attaches it to the tab the way OsmAnd's own map screen does, and opens it over Iraq until the map
 * has a position of its own.
 *
 * OsmAnd does not start with the app: the first engine starts it. The device's position is followed only
 * while the tab is open and only once the location permission has been granted, which the page asks for when
 * its location button is pressed.
 */
final class OsmAndMapEngine implements MapEngine {

    private static final double START_LATITUDE = 33.3;
    private static final double START_LONGITUDE = 44.4;
    private static final int START_ZOOM = 6;

    private final OsmandApplication app;
    private final MapViewWithLayers view;
    private final OsmandMapTileView mapView;
    private boolean destroyed;
    private boolean resumed;
    private boolean followingLocation;

    OsmAndMapEngine(Context context) {
        app = (OsmandApplication) context.getApplicationContext();
        final long start = System.currentTimeMillis();
        ((PhogramBaseApplication) app).startOsmAnd();
        final long started = System.currentTimeMillis();
        view = new MapViewWithLayers(context);
        mapView = app.getOsmandMap().getMapView();
        app.getMapViewTrackingUtilities().setMapView(mapView);
        if (!app.getSettings().isLastKnownMapLocation()) {
            mapView.setLatLon(START_LATITUDE, START_LONGITUDE);
            mapView.setIntZoom(START_ZOOM);
        }
        if (app.isApplicationInitializing()) {
            app.checkApplicationIsBeingInitialized(new AppInitializeListener() {
                @Override
                public void onFinish(AppInitializer init) {
                    if (!destroyed) {
                        FileLog.d("cogram: map engine ready after " + (System.currentTimeMillis() - start) + " ms");
                        app.getOsmandMap().setupRenderingView();
                        mapView.refreshMap(true);
                    }
                }
            });
        } else {
            app.getOsmandMap().setupRenderingView();
        }
        FileLog.d("cogram: OsmAnd started in " + (started - start) + " ms, map view created in "
                + (System.currentTimeMillis() - started) + " ms, initializing=" + app.isApplicationInitializing());
    }

    @Override
    public View getView() {
        return view;
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        // The map runs under the header and the tab bar, as Telegram's pages do; its center stays the view's.
    }

    @Override
    public void moveToMyLocation() {
        startFollowingLocation();
        // Without a fix yet OsmAnd says so and links the map to the position, so the map moves when one comes.
        app.getMapViewTrackingUtilities().backToLocationImpl();
    }

    @Override
    public void onLocationPermissionGranted() {
        startFollowingLocation();
    }

    private void startFollowingLocation() {
        if (resumed && !followingLocation && OsmAndLocationProvider.isLocationPermissionAvailable(app)) {
            app.getLocationProvider().resumeAllUpdates();
            followingLocation = true;
        }
    }

    private void stopFollowingLocation() {
        if (followingLocation) {
            app.getLocationProvider().pauseAllUpdates();
            followingLocation = false;
        }
    }

    @Override
    public float getRotation() {
        return mapView.getRotate();
    }

    @Override
    public void resetRotation() {
        // As OsmAnd's own compass button does.
        mapView.resetRotation();
        app.getMapViewTrackingUtilities().setLastResetRotationToNorth(System.currentTimeMillis());
        if (app.getSettings().getCompassMode() == CompassMode.MANUALLY_ROTATED) {
            app.getSettings().setManuallyMapRotation(0);
        }
    }

    @Override
    public void onResume() {
        resumed = true;
        mapView.refreshMap(true);
        view.onResume();
        startFollowingLocation();
    }

    @Override
    public void onPause() {
        resumed = false;
        // The position is not followed while another tab is open, so the GPS does not use the battery.
        stopFollowingLocation();
        view.onPause();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        stopFollowingLocation();
        view.onDestroy();
        app.getMapViewTrackingUtilities().setMapView(null);
    }
}
