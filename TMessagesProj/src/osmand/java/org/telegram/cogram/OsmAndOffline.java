package org.telegram.cogram;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Xml;

import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.data.LatLon;
import net.osmand.osm.AbstractPoiType;
import net.osmand.osm.MapPoiTypes;
import net.osmand.router.RoutePlannerFrontEnd;
import net.osmand.router.RouteResultPreparation;
import net.osmand.router.RouteSegmentResult;
import net.osmand.router.RoutingConfiguration;
import net.osmand.router.RoutingContext;
import net.osmand.search.SearchUICore;
import net.osmand.search.core.SearchResult;
import net.osmand.search.core.SearchSettings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Offline search and routing with OsmAnd's core library (OsmAnd-java) over OsmAnd's map file of the region, without
 * OsmAnd's app. The file comes from OsmAnd's official download server (open data from OpenStreetMap), through
 * Android's DownloadManager; it is used only once its zip's checksums pass and OsmAnd's reader opens it. MapLibre
 * draws the map; this only searches and routes.
 */
final class OsmAndOffline {

    /** OsmAnd's file for Iraq: addresses, places and roads (and map data, which Phogram does not draw). */
    static final String REGION_FILE = "Iraq_asia_2.obf";
    private static final String DOWNLOAD_URL = "https://download.osmand.net/download?standard=yes&file=" + REGION_FILE + ".zip";
    private static final String PREFERENCES = "cogram_osmand";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_SIZE = "zip_size";
    private static final int POLL_MS = 500;
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_SEARCH_RESULTS = 40;
    /** Java routing works within the app's heap; long routes across the country need some room. */
    private static final int ROUTING_MEMORY_MB = 96;

    private static final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private static final ExecutorService routeExecutor = Executors.newSingleThreadExecutor();
    private static final List<Runnable> listeners = new ArrayList<>();
    private static boolean polling;
    private static boolean installing;
    private static float progress;
    private static boolean sizeAsked;

    // Opened once, then kept: OsmAnd's search core with its place types, and the reader of the region's file.
    private static SearchUICore core;
    private static BinaryMapIndexReader reader;
    private static File readerFile;
    private static RoutingConfiguration.Builder routingRules;

    private boolean destroyed;
    private Runnable downloadListener;
    private final Runnable listener = () -> {
        if (!destroyed && downloadListener != null) {
            downloadListener.run();
        }
    };
    private volatile int searchRequest;
    private volatile int routeRequest;

    OsmAndOffline(Context context) {
        listeners.add(listener);
        startPolling();
        askSize();
    }

    void setDownloadListener(Runnable listener) {
        downloadListener = listener;
    }

    /** The map's center; the region's file is offered wherever the map is, as Phogram serves Iraq. */
    void setCenter(double latitude, double longitude, double zoom) {
    }

    // The region's file on the phone.

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static File dir() {
        return new File(MapData.mapsDir(), "osmand");
    }

    static File installedFile() {
        final File file = new File(dir(), REGION_FILE);
        return file.exists() && file.length() > 0 ? file : null;
    }

    private static boolean isDownloading() {
        return preferences().getLong(KEY_DOWNLOAD_ID, -1) != -1 || installing;
    }

    private static void notifyListeners() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }

    /** The zip's size, from OsmAnd's server, for the offer's card; asked once per run of the app. */
    private static void askSize() {
        if (sizeAsked || installedFile() != null) return;
        sizeAsked = true;
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(DOWNLOAD_URL).openConnection();
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                final long size = connection.getResponseCode() == 200 ? connection.getContentLengthLong() : -1;
                if (size > 0) {
                    preferences().edit().putLong(KEY_SIZE, size).apply();
                    AndroidUtilities.runOnUIThread(OsmAndOffline::notifyListeners);
                }
            } catch (Exception e) {
                FileLog.e(e);
                sizeAsked = false;
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    MapEngine.Download getOfferedDownload() {
        if (installedFile() != null && !isDownloading()) return null;
        final long size = preferences().getLong(KEY_SIZE, 0);
        final String name = LocaleController.getString(R.string.PhogramMapsSearchData);
        return new MapEngine.Download(name, size > 0 ? AndroidUtilities.formatFileSize(size) : "", isDownloading(),
                installing ? 100 : Math.max(0, Math.min(100, progress)));
    }

    private static DownloadManager downloads() {
        return (DownloadManager) ApplicationLoader.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    void startOfferedDownload() {
        if (isDownloading()) return;
        final File folder = dir();
        folder.mkdirs();
        final File part = new File(folder, REGION_FILE + ".zip.part");
        part.delete();
        final DownloadManager.Request request = new DownloadManager.Request(Uri.parse(DOWNLOAD_URL))
                .setTitle(LocaleController.getString(R.string.PhogramMapsSearchData))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(part));
        final long id = downloads().enqueue(request);
        preferences().edit().putLong(KEY_DOWNLOAD_ID, id).apply();
        progress = 0;
        notifyListeners();
        startPolling();
    }

    void cancelOfferedDownload() {
        final long id = preferences().getLong(KEY_DOWNLOAD_ID, -1);
        if (id != -1) {
            downloads().remove(id);
        }
        preferences().edit().remove(KEY_DOWNLOAD_ID).apply();
        new File(dir(), REGION_FILE + ".zip.part").delete();
        notifyListeners();
    }

    private static void startPolling() {
        if (polling || preferences().getLong(KEY_DOWNLOAD_ID, -1) == -1) return;
        polling = true;
        AndroidUtilities.runOnUIThread(OsmAndOffline::poll, POLL_MS);
    }

    private static void poll() {
        final long id = preferences().getLong(KEY_DOWNLOAD_ID, -1);
        if (id == -1) {
            polling = false;
            return;
        }
        // DownloadManager's database is asked off the main thread, so the map keeps its frames during a download.
        Utilities.globalQueue.postRunnable(() -> {
            int status = DownloadManager.STATUS_FAILED;
            float done = -1;
            try (Cursor cursor = downloads().query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor != null && cursor.moveToFirst()) {
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    final long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    final long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    done = total > 0 ? Math.min(100, bytes * 100f / total) : 0;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            final int result = status;
            final float percent = done;
            AndroidUtilities.runOnUIThread(() -> onPolled(id, result, percent));
        });
    }

    private static void onPolled(long id, int status, float percent) {
        if (id != preferences().getLong(KEY_DOWNLOAD_ID, -1)) {
            polling = false;
            startPolling();
            return;
        }
        if (percent >= 0) {
            progress = percent;
        }
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            polling = false;
            preferences().edit().remove(KEY_DOWNLOAD_ID).apply();
            install();
        } else if (status == DownloadManager.STATUS_FAILED) {
            polling = false;
            preferences().edit().remove(KEY_DOWNLOAD_ID).apply();
            new File(dir(), REGION_FILE + ".zip.part").delete();
        } else {
            AndroidUtilities.runOnUIThread(OsmAndOffline::poll, POLL_MS);
        }
        notifyListeners();
    }

    /**
     * Unpacks the downloaded zip next to it. Java's zip reader checks every entry's CRC-32, the size must be what
     * OsmAnd's server announced, and OsmAnd's reader must open the result; otherwise everything is deleted.
     */
    private static void install() {
        installing = true;
        notifyListeners();
        Utilities.globalQueue.postRunnable(() -> {
            final File folder = dir();
            final File zip = new File(folder, REGION_FILE + ".zip.part");
            final File temp = new File(folder, REGION_FILE + ".tmp");
            final File target = new File(folder, REGION_FILE);
            boolean ok = false;
            try {
                final long expected = preferences().getLong(KEY_SIZE, 0);
                if (expected > 0 && zip.length() != expected) {
                    throw new IllegalStateException("map file size " + zip.length() + " instead of " + expected);
                }
                try (ZipInputStream in = new ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(zip)))) {
                    ZipEntry entry;
                    boolean found = false;
                    while ((entry = in.getNextEntry()) != null) {
                        if (entry.isDirectory() || !entry.getName().endsWith(".obf")) continue;
                        try (OutputStream out = new FileOutputStream(temp)) {
                            final byte[] buffer = new byte[1 << 16];
                            int n;
                            while ((n = in.read(buffer)) > 0) {
                                out.write(buffer, 0, n);
                            }
                        }
                        found = true;
                        break;
                    }
                    if (!found) throw new IllegalStateException("no map file in the download");
                }
                try (RandomAccessFile raf = new RandomAccessFile(temp, "r")) {
                    final BinaryMapIndexReader check = new BinaryMapIndexReader(raf, temp);
                    if (check.getIndexes().isEmpty()) throw new IllegalStateException("empty map file");
                }
                closeReader();
                target.delete();
                ok = temp.renameTo(target);
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                zip.delete();
                if (!ok) temp.delete();
            }
            AndroidUtilities.runOnUIThread(() -> {
                installing = false;
                notifyListeners();
            });
        });
    }

    private static synchronized void closeReader() {
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        reader = null;
        readerFile = null;
    }

    /** OsmAnd's reader of the region's file, opened on first use; null without the file. */
    private static synchronized BinaryMapIndexReader reader() throws Exception {
        final File file = installedFile();
        if (file == null) {
            closeReader();
            return null;
        }
        if (reader == null || !file.equals(readerFile)) {
            closeReader();
            reader = new BinaryMapIndexReader(new RandomAccessFile(file, "r"), file);
            readerFile = file;
        }
        return reader;
    }

    private static String language() {
        final String lang = LocaleController.getInstance().getCurrentLocale().getLanguage();
        return TextUtils.isEmpty(lang) ? "en" : lang;
    }

    private static synchronized SearchUICore core() {
        if (core == null) {
            final MapPoiTypes types = MapPoiTypes.getDefault();
            types.setPoiTranslator(new Translator(language()));
            core = new SearchUICore(types, language(), false);
            core.init();
        }
        return core;
    }

    // Search: OsmAnd's offline search over the region's file, around the given point, in the app's language.

    boolean hasData() {
        return installedFile() != null;
    }

    void search(String query, double latitude, double longitude, MapEngine.SearchCallback callback) {
        final int request = ++searchRequest;
        final LatLon center = new LatLon(latitude, longitude);
        searchExecutor.execute(() -> {
            if (request != searchRequest) return;
            final List<MapEngine.Place> places = new ArrayList<>();
            try {
                final BinaryMapIndexReader file = reader();
                if (file != null) {
                    final SearchUICore search = core();
                    final List<BinaryMapIndexReader> files = new ArrayList<>();
                    files.add(file);
                    final SearchSettings settings = search.getSearchSettings().setLang(language(), false).setOriginalLocation(center);
                    settings.setOfflineIndexes(files);
                    search.updateSettings(settings);
                    final SearchUICore.SearchResultCollection results = search.immediateSearch(query, center);
                    for (SearchResult result : results.getCurrentSearchResults()) {
                        if (request != searchRequest) return;
                        if (result.location == null || TextUtils.isEmpty(result.localeName)) continue;
                        places.add(new MapEngine.Place(result.localeName, describe(result),
                                result.location.getLatitude(), result.location.getLongitude()));
                        if (places.size() >= MAX_SEARCH_RESULTS) break;
                    }
                }
            } catch (Throwable e) {
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
        return TextUtils.join("، ", parts);
    }

    // Routing: OsmAnd's car router in Java over the region's file.

    void route(double fromLatitude, double fromLongitude, double toLatitude, double toLongitude, Utilities.Callback<MapEngine.Route> callback) {
        final int request = ++routeRequest;
        routeExecutor.execute(() -> {
            if (request != routeRequest) return;
            MapEngine.Route route = null;
            try {
                final BinaryMapIndexReader file = reader();
                if (file != null) {
                    route = calculate(file, new LatLon(fromLatitude, fromLongitude), new LatLon(toLatitude, toLongitude));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final MapEngine.Route result = route;
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && request == routeRequest) {
                    callback.run(result);
                }
            });
        });
    }

    private static MapEngine.Route calculate(BinaryMapIndexReader file, LatLon start, LatLon end) throws Exception {
        synchronized (OsmAndOffline.class) {
            if (routingRules == null) {
                routingRules = RoutingConfiguration.getDefault();
            }
        }
        // The region's file holds what the route needs; OsmAnd's world regions are not read for missing maps.
        RoutePlannerFrontEnd.CALCULATE_MISSING_MAPS = false;
        final RoutingConfiguration config = routingRules.build("car",
                new RoutingConfiguration.RoutingMemoryLimits(ROUTING_MEMORY_MB, 0), new LinkedHashMap<>());
        final RoutePlannerFrontEnd planner = new RoutePlannerFrontEnd();
        final RoutingContext context = planner.buildRoutingContext(config, null, new BinaryMapIndexReader[] { file },
                RoutePlannerFrontEnd.RouteCalculationMode.COMPLEX);
        final RouteResultPreparation.RouteCalcResult result = planner.searchRoute(context, start, end, null);
        if (result == null || !result.isCorrect() || result.getList() == null || result.getList().isEmpty()) {
            return null;
        }
        final List<double[]> points = new ArrayList<>();
        float meters = 0;
        float seconds = 0;
        for (RouteSegmentResult segment : result.getList()) {
            meters += segment.getDistance();
            seconds += segment.getSegmentTime();
            final int from = segment.getStartPointIndex();
            final int to = segment.getEndPointIndex();
            final int step = from <= to ? 1 : -1;
            for (int i = from; ; i += step) {
                final LatLon point = segment.getPoint(i);
                points.add(new double[] { point.getLatitude(), point.getLongitude() });
                if (i == to) break;
            }
        }
        return new MapEngine.Route(points, meters, seconds);
    }

    void destroy() {
        destroyed = true;
        listeners.remove(listener);
        searchRequest++;
        routeRequest++;
    }

    /**
     * Names of OsmAnd's place types in the app's language and in English, from OsmAnd's phrases (copied into the
     * assets by fetch-osmand.sh). A phrase's synonyms follow its name after a semicolon.
     */
    private static final class Translator implements MapPoiTypes.PoiTranslator {

        private final Map<String, String> local;
        private final Map<String, String> english;

        Translator(String language) {
            english = load("en");
            final Map<String, String> own = "en".equals(language) ? english : load(language);
            local = own.isEmpty() ? english : own;
        }

        private static Map<String, String> load(String language) {
            final Map<String, String> phrases = new HashMap<>();
            try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("osmand_phrases/" + language + ".xml")) {
                final XmlPullParser parser = Xml.newPullParser();
                parser.setInput(in, "UTF-8");
                String name = null;
                for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                    if (event == XmlPullParser.START_TAG && "string".equals(parser.getName())) {
                        name = parser.getAttributeValue(null, "name");
                    } else if (event == XmlPullParser.TEXT && name != null) {
                        phrases.put(name, parser.getText().replace("\\'", "'"));
                    } else if (event == XmlPullParser.END_TAG) {
                        name = null;
                    }
                }
            } catch (Exception e) {
                // No phrases in this language: English names are used.
            }
            return phrases;
        }

        private static String name(Map<String, String> phrases, String key) {
            final String value = phrases.get("poi_" + key);
            if (value == null) return null;
            final int semicolon = value.indexOf(';');
            return semicolon > 0 ? value.substring(0, semicolon) : value;
        }

        private static String synonyms(Map<String, String> phrases, String key) {
            final String value = phrases.get("poi_" + key);
            if (value == null) return "";
            final int semicolon = value.indexOf(';');
            return semicolon > 0 ? value.substring(semicolon + 1) : "";
        }

        private static AbstractPoiType base(AbstractPoiType type) {
            return type.getBaseLangType() != null ? type.getBaseLangType() : type;
        }

        @Override
        public String getTranslation(AbstractPoiType type) {
            return getTranslation(base(type).getFormattedKeyName());
        }

        @Override
        public String getTranslation(String keyName) {
            return name(local, keyName);
        }

        @Override
        public String getEnTranslation(AbstractPoiType type) {
            return getEnTranslation(base(type).getFormattedKeyName());
        }

        @Override
        public String getEnTranslation(String keyName) {
            final String value = name(english, keyName);
            return value != null ? value : keyName.replace('_', ' ');
        }

        @Override
        public String getSynonyms(AbstractPoiType type) {
            return getSynonyms(base(type).getFormattedKeyName());
        }

        @Override
        public String getSynonyms(String keyName) {
            return synonyms(local, keyName);
        }

        @Override
        public String getAllLanguagesTranslationSuffix() {
            return "all languages";
        }
    }
}
