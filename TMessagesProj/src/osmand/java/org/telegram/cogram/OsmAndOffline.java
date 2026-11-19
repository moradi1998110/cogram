package org.telegram.cogram;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import net.osmand.data.LatLon;
import net.osmand.plus.AppInitializeListener;
import net.osmand.plus.AppInitializer;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.download.DownloadActivityType;
import net.osmand.plus.download.DownloadIndexesThread;
import net.osmand.plus.download.DownloadResources;
import net.osmand.plus.download.IndexItem;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.search.SearchUICore;
import net.osmand.search.core.SearchResult;

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
 * OsmAnd without its map: the offline search over OsmAnd's map files and the downloads of those files. Another
 * engine draws the map and tells this where its center is. OsmAnd starts with the first instance.
 */
final class OsmAndOffline {

    /** OsmAnd's small world map, the one OsmAnd itself ships inside its app (World_basemap_mini.obf). */
    private static final String WORLD_MAP_INDEX = "World_basemap_mini_2.obf.zip";
    /** Below this zoom the center says little about which region the user means. */
    private static final double REGION_OFFER_MIN_ZOOM = 5;
    private static final int MAX_SEARCH_RESULTS = 40;

    private final OsmandApplication app;
    private boolean ready;
    private boolean destroyed;
    private Runnable readyListener;
    private Runnable downloadListener;
    private IndexItem offeredItem;
    private int offerGeneration;
    private double centerLatitude;
    private double centerLongitude;
    private double centerZoom;
    private final ExecutorService offerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private volatile int searchRequest;

    OsmAndOffline(Context context) {
        app = (OsmandApplication) context.getApplicationContext();
        ((PhogramBaseApplication) app).startOsmAnd();
        if (app.isApplicationInitializing()) {
            app.checkApplicationIsBeingInitialized(new AppInitializeListener() {
                @Override
                public void onFinish(AppInitializer init) {
                    if (!destroyed) {
                        markReady();
                    }
                }
            });
        } else {
            markReady();
        }
    }

    /** Runs once OsmAnd has read its map files, right away if it has. */
    void setOnReadyListener(Runnable listener) {
        readyListener = listener;
        if (ready && listener != null) {
            listener.run();
        }
    }

    private void markReady() {
        ready = true;
        final DownloadIndexesThread thread = app.getDownloadThread();
        thread.setUiActivity(downloadEvents);
        if (thread.shouldDownloadIndexes()) {
            // The list of map files on OsmAnd's servers; the offers follow when it arrives.
            thread.runReloadIndexFilesSilent();
        } else {
            updateOffer();
        }
        if (readyListener != null) {
            readyListener.run();
        }
    }

    /** Where the map's center is now; the offer follows it. */
    void setCenter(double latitude, double longitude, double zoom) {
        centerLatitude = latitude;
        centerLongitude = longitude;
        centerZoom = zoom;
        if (ready) {
            updateOffer();
        }
    }

    // Map downloads: OsmAnd's world map first, then the region at the map's center, from OsmAnd's servers through
    // OsmAnd's own download queue, which also indexes them for the search when done.

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
            updateOffer();
        }
    };

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
        if (centerZoom < REGION_OFFER_MIN_ZOOM) {
            setOffer(null);
            return;
        }
        final LatLon center = new LatLon(centerLatitude, centerLongitude);
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

    void setDownloadListener(Runnable listener) {
        downloadListener = listener;
    }

    MapEngine.Download getOfferedDownload() {
        final IndexItem item = offeredItem;
        if (item == null) return null;
        final DownloadIndexesThread thread = app.getDownloadThread();
        final boolean downloading = thread.isDownloading(item);
        final float progress = downloading && thread.getCurrentDownloadingItem() == item ? thread.getCurrentDownloadProgress() : 0;
        final String name = WORLD_MAP_INDEX.equals(item.getFileName())
                ? LocaleController.getString(R.string.PhogramMapsWorldMap)
                : item.getVisibleName(app, app.getRegions(), false);
        return new MapEngine.Download(name, item.getSizeDescription(app), downloading, Math.max(0, Math.min(100, progress)));
    }

    void startOfferedDownload() {
        if (offeredItem != null) {
            app.getDownloadThread().runDownloadFiles(offeredItem);
            notifyDownloadListener();
        }
    }

    void cancelOfferedDownload() {
        if (offeredItem != null) {
            app.getDownloadThread().cancelDownload(offeredItem);
            notifyDownloadListener();
        }
    }

    // Search: OsmAnd's own offline search over the downloaded map files, around the given point, in the app's language.

    void search(String query, double latitude, double longitude, MapEngine.SearchCallback callback) {
        final int request = ++searchRequest;
        final LatLon center = new LatLon(latitude, longitude);
        searchExecutor.execute(() -> {
            if (request != searchRequest) return;
            final List<MapEngine.Place> places = new ArrayList<>();
            try {
                final SearchUICore core = app.getSearchUICore().getCore();
                final OsmandSettings settings = app.getSettings();
                core.updateSettings(core.getSearchSettings().setOriginalLocation(center)
                        .setLangs(app.getLanguage(), settings.MAP_PREFERRED_LOCALE.get(), settings.MAP_TRANSLITERATE_NAMES.get()));
                final SearchUICore.SearchResultCollection results = core.immediateSearch(query, center);
                for (SearchResult result : results.getCurrentSearchResults()) {
                    if (request != searchRequest) return;
                    if (result.location == null || TextUtils.isEmpty(result.localeName)) continue;
                    places.add(new MapEngine.Place(result.localeName, describe(result),
                            result.location.getLatitude(), result.location.getLongitude()));
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

    void destroy() {
        destroyed = true;
        if (ready) {
            // A running download goes on in OsmAnd's queue; only this page stops listening to it.
            app.getDownloadThread().resetUiActivity(downloadEvents);
        }
        offerExecutor.shutdown();
        searchRequest++;
        searchExecutor.shutdown();
    }
}
