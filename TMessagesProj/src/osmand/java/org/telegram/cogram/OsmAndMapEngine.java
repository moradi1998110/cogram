package org.telegram.cogram;

import android.content.Context;
import android.view.View;

import net.osmand.plus.AppInitializeListener;
import net.osmand.plus.AppInitializer;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.views.MapViewWithLayers;
import net.osmand.plus.views.OsmandMapTileView;

import org.telegram.messenger.FileLog;

/**
 * OsmAnd's map in Phogram's Maps tab (phase 0: the map alone, without OsmAnd's own screens). OsmAnd keeps
 * one map view for the whole app; this attaches it to the tab the way OsmAnd's own map screen does, and
 * opens it over Iraq until the map has a position of its own.
 */
final class OsmAndMapEngine implements MapEngine {

    private static final double START_LATITUDE = 33.3;
    private static final double START_LONGITUDE = 44.4;
    private static final int START_ZOOM = 6;

    private final OsmandApplication app;
    private final MapViewWithLayers view;
    private final OsmandMapTileView mapView;
    private boolean destroyed;

    OsmAndMapEngine(Context context) {
        app = (OsmandApplication) context.getApplicationContext();
        final long start = System.currentTimeMillis();
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
        FileLog.d("cogram: map view created in " + (System.currentTimeMillis() - start) + " ms, initializing=" + app.isApplicationInitializing());
    }

    @Override
    public View getView() {
        return view;
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        // Phase 0 lets the map run under the glass header and tab bar, as Telegram's pages do; phase 1 moves
        // the map's own controls and center out from under them.
    }

    @Override
    public void onResume() {
        mapView.refreshMap(true);
        view.onResume();
    }

    @Override
    public void onPause() {
        view.onPause();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        view.onDestroy();
        app.getMapViewTrackingUtilities().setMapView(null);
    }
}
