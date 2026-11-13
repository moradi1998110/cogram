package org.telegram.cogram;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;

import net.osmand.data.LatLon;
import net.osmand.data.RotatedTileBox;
import net.osmand.search.SearchUICore;
import net.osmand.search.core.SearchResult;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.map.IMapLocationListener;
import net.osmand.plus.AppInitializeListener;
import net.osmand.plus.AppInitializer;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.base.MapViewTrackingUtilities;
import net.osmand.plus.download.DownloadActivityType;
import net.osmand.plus.download.DownloadIndexesThread;
import net.osmand.plus.download.DownloadResources;
import net.osmand.plus.download.IndexItem;
import net.osmand.plus.settings.enums.CompassMode;
import net.osmand.plus.views.MapViewWithLayers;
import net.osmand.plus.views.OsmandMapTileView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.siacs.conversations.PhogramBaseApplication;

/**
 * OsmAnd's map in Phogram's Maps tab, without OsmAnd's own screens. OsmAnd keeps one map view for the whole
 * app; this attaches it to the tab the way OsmAnd's own map screen does, and opens it over Iraq until the map
 * has a position of its own.
 *
 * OsmAnd does not start with the app: the first engine starts it. The app does not ship OsmAnd's world map;
 * the engine offers it and the regional maps for download from OsmAnd's servers. The device's position is followed only
 * while the tab is open and only once the location permission has been granted, which the page asks for when
 * its location button is pressed.
 */
final class OsmAndMapEngine implements MapEngine {

    private static final double START_LATITUDE = 33.3;
    private static final double START_LONGITUDE = 44.4;
    private static final int START_ZOOM = 6;
    private static final int MY_LOCATION_ZOOM = 15;
    /** OsmAnd's small world map, the one OsmAnd itself ships inside its app (World_basemap_mini.obf). */
    private static final String WORLD_MAP_INDEX = "World_basemap_mini_2.obf.zip";
    /** Below this zoom the center says little about which region the user means. */
    private static final int REGION_OFFER_MIN_ZOOM = 5;
    private static final long OFFER_DELAY_MS = 600;
    private static final int MAX_SEARCH_RESULTS = 40;
    private static final int PLACE_ZOOM = 16;

    private final OsmandApplication app;
    private final MapViewWithLayers view;
    private final OsmandMapTileView mapView;
    private boolean destroyed;
    private boolean resumed;
    private boolean followingLocation;
    private boolean ready;
    private Runnable readyListener;
    private Runnable downloadListener;
    private IndexItem offeredItem;
    private int offerGeneration;
    private final ExecutorService offerExecutor = Executors.newSingleThreadExecutor();

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
                        markReady();
                    }
                }
            });
        } else {
            app.getOsmandMap().setupRenderingView();
            markReady();
        }
        FileLog.d("cogram: OsmAnd started in " + (started - start) + " ms, map view created in "
                + (System.currentTimeMillis() - started) + " ms, initializing=" + app.isApplicationInitializing());
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

    private void markReady() {
        ready = true;
        if (readyListener != null) {
            readyListener.run();
        }
        startDownloadOffers();
    }

    // Map downloads. The app ships without OsmAnd's world map, so the page offers it first; once it is there, the
    // page offers the map of the region at the map's center, as OsmAnd's own map screen does. Files come from
    // OsmAnd's servers through OsmAnd's own download queue, which also indexes them for the map when done.

    private void startDownloadOffers() {
        final DownloadIndexesThread thread = app.getDownloadThread();
        thread.setUiActivity(downloadEvents);
        mapView.addMapLocationListener(mapLocationListener);
        if (thread.shouldDownloadIndexes()) {
            // The list of map files on OsmAnd's servers; the offers follow when it arrives.
            thread.runReloadIndexFilesSilent();
        } else {
            updateOffer();
        }
    }

    private final DownloadIndexesThread.DownloadEvents downloadEvents = new DownloadIndexesThread.DownloadEvents() {
        @Override
        public void onUpdatedIndexesList() {
            updateOffer();
        }

        @Override
        public void downloadInProgress() {
            notifyDownloadListener();
        }

        @Override
        public void downloadingError(@NonNull String error) {
            notifyDownloadListener();
        }

        @Override
        public void downloadHasFinished() {
            mapView.refreshMap(true);
            updateOffer();
        }
    };

    // OsmAnd reports moves from its animation thread too, so the check runs on the main thread after the map settles.
    private final IMapLocationListener mapLocationListener =
            (latitude, longitude, source) -> AndroidUtilities.runOnUIThread(this::updateOfferLater);

    private final Runnable updateOffer = this::updateOffer;

    private void updateOfferLater() {
        AndroidUtilities.cancelRunOnUIThread(updateOffer);
        AndroidUtilities.runOnUIThread(updateOffer, OFFER_DELAY_MS);
    }

    private void updateOffer() {
        if (destroyed) return;
        final DownloadIndexesThread thread = app.getDownloadThread();
        if (offeredItem != null && thread.isDownloading(offeredItem)) {
            // A running download stays on the card wherever the map is moved.
            notifyDownloadListener();
            return;
        }
        final int generation = ++offerGeneration;
        final IndexItem world = thread.getIndexes().getIndexItem(WORLD_MAP_INDEX);
        if (world != null && !world.isDownloaded()) {
            setOffer(world);
            return;
        }
        if (mapView.getZoom() < REGION_OFFER_MIN_ZOOM) {
            setOffer(null);
            return;
        }
        final LatLon center = new LatLon(mapView.getLatitude(), mapView.getLongitude());
        offerExecutor.execute(() -> {
            IndexItem region = null;
            try {
                region = DownloadResources.findSmallestIndexItemAt(app, center, DownloadActivityType.NORMAL_FILE);
            } catch (Exception e) {
                FileLog.e(e);
            }
            final IndexItem found = region;
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && generation == offerGeneration) {
                    setOffer(found != null && !found.isDownloaded() ? found : null);
                }
            });
        });
    }

    private void setOffer(IndexItem item) {
        offeredItem = item;
        notifyDownloadListener();
    }

    private void notifyDownloadListener() {
        if (!destroyed && downloadListener != null) {
            downloadListener.run();
        }
    }

    @Override
    public void setDownloadListener(Runnable listener) {
        downloadListener = listener;
    }

    @Override
    public Download getOfferedDownload() {
        final IndexItem item = offeredItem;
        if (item == null) return null;
        final DownloadIndexesThread thread = app.getDownloadThread();
        final boolean downloading = thread.isDownloading(item);
        final float progress = downloading && thread.getCurrentDownloadingItem() == item ? thread.getCurrentDownloadProgress() : 0;
        final String name = WORLD_MAP_INDEX.equals(item.getFileName())
                ? LocaleController.getString(R.string.PhogramMapsWorldMap)
                : item.getVisibleName(app, app.getRegions(), false);
        return new Download(name, item.getSizeDescription(app), downloading, Math.max(0, Math.min(100, progress)));
    }

    @Override
    public void startOfferedDownload() {
        if (offeredItem != null) {
            app.getDownloadThread().runDownloadFiles(offeredItem);
            notifyDownloadListener();
        }
    }

    // Search: OsmAnd's own offline search over the downloaded maps, around the map's center, in the app's language.

    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private volatile int searchRequest;

    @Override
    public void search(String query, SearchCallback callback) {
        final int request = ++searchRequest;
        final LatLon center = new LatLon(mapView.getLatitude(), mapView.getLongitude());
        searchExecutor.execute(() -> {
            if (request != searchRequest) return;
            final List<Place> places = new ArrayList<>();
            try {
                final SearchUICore core = app.getSearchUICore().getCore();
                final OsmandSettings settings = app.getSettings();
                core.updateSettings(core.getSearchSettings().setOriginalLocation(center)
                        .setLangs(app.getLanguage(), settings.MAP_PREFERRED_LOCALE.get(), settings.MAP_TRANSLITERATE_NAMES.get()));
                final SearchUICore.SearchResultCollection results = core.immediateSearch(query, center);
                for (SearchResult result : results.getCurrentSearchResults()) {
                    if (request != searchRequest) return;
                    if (result.location == null || TextUtils.isEmpty(result.localeName)) continue;
                    places.add(new Place(result.localeName, describe(result), result.location.getLatitude(), result.location.getLongitude()));
                    if (places.size() >= MAX_SEARCH_RESULTS) break;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && request == searchRequest) {
                    callback.onResults(query, places);
                }
            });
        });
    }

    private static String describe(SearchResult result) {
        final List<String> parts = new ArrayList<>();
        for (String part : new String[] { result.addressName, result.localeRelatedObjectName, result.cityName }) {
            if (!TextUtils.isEmpty(part) && !part.equals(result.localeName) && !parts.contains(part)) {
                parts.add(part);
            }
        }
        return TextUtils.join(", ", parts);
    }

    // The shown place: the map moves to it, and each map frame reports where it is so the page keeps its pin on it.

    private Place shownPlace;
    private PinListener pinListener;
    private float lastPinX = Float.NaN;
    private float lastPinY = Float.NaN;

    private final OsmandMapTileView.OnDrawMapListener pinTracker = () -> {
        final Place place = shownPlace;
        if (place == null) return;
        final RotatedTileBox box = mapView.getRotatedTileBox();
        final float x = box.getPixXFromLatLon(place.latitude, place.longitude);
        final float y = box.getPixYFromLatLon(place.latitude, place.longitude);
        AndroidUtilities.runOnUIThread(() -> reportPin(place, x, y));
    };

    private void reportPin(Place place, float x, float y) {
        if (destroyed || place != shownPlace || pinListener == null) return;
        if (x == lastPinX && y == lastPinY) return;
        lastPinX = x;
        lastPinY = y;
        pinListener.onPinMoved(x, y);
    }

    @Override
    public void showPlace(Place place, PinListener listener) {
        shownPlace = place;
        pinListener = listener;
        lastPinX = Float.NaN;
        lastPinY = Float.NaN;
        if (place == null) {
            mapView.setOnDrawMapListener(null);
            return;
        }
        mapView.setOnDrawMapListener(pinTracker);
        // The map no longer follows the device's position, or it would pull away from the place.
        app.getMapViewTrackingUtilities().setMapLinkedToLocation(false);
        mapView.getAnimatedDraggingThread().startMoving(place.latitude, place.longitude, Math.max(mapView.getZoom(), PLACE_ZOOM));
        mapView.refreshMap();
    }

    @Override
    public void cancelOfferedDownload() {
        if (offeredItem != null) {
            app.getDownloadThread().cancelDownload(offeredItem);
            notifyDownloadListener();
        }
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        // The map runs under the header and the tab bar, as Telegram's pages do; its center stays the view's.
    }

    @Override
    public void moveToMyLocation() {
        startFollowingLocation();
        final MapViewTrackingUtilities tracking = app.getMapViewTrackingUtilities();
        final OsmAndLocationProvider provider = app.getLocationProvider();
        final boolean hasPosition = provider.getLastKnownLocation() != null || provider.getLastStaleKnownLocation() != null;
        if (hasPosition && tracking.isMapLinkedToLocation()) {
            // OsmAnd only moves a map that is not following the position yet; the button should also bring the
            // map close in, as Telegram's does, so the link is let go first and taken again by the move.
            tracking.setMapLinkedToLocation(false);
        }
        // Without a fix yet OsmAnd says so and links the map to the position, so the map moves when one comes.
        tracking.backToLocationImpl(MY_LOCATION_ZOOM, true);
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
        AndroidUtilities.cancelRunOnUIThread(updateOffer);
        if (ready) {
            mapView.removeMapLocationListener(mapLocationListener);
            // A running download goes on in OsmAnd's queue; only this page stops listening to it.
            app.getDownloadThread().resetUiActivity(downloadEvents);
        }
        offerExecutor.shutdown();
        searchRequest++;
        searchExecutor.shutdown();
        shownPlace = null;
        mapView.setOnDrawMapListener(null);
        stopFollowingLocation();
        view.onDestroy();
        app.getMapViewTrackingUtilities().setMapView(null);
    }
}
