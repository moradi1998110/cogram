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
import net.osmand.router.TurnType;
import net.osmand.binary.RouteDataObject;
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
    private static final int MAX_SEARCH_RESULTS = 200;
    /** Java routing works within the app's large heap (largeHeap); routes across the country need room for their road data. */
    private static final int ROUTING_MEMORY_MB = 256;

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
    private volatile int estimateRequest;
    private static boolean warmedUp;

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
            net.osmand.router.PhogramHHCache.clear();
            warmedUp = false;
            for (int i = 0; i < readerCopies.length; i++) {
                if (readerCopies[i] != null) {
                    try {
                        readerCopies[i].close();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    readerCopies[i] = null;
                }
            }
            try {
                reader.close();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        reader = null;
        readerFile = null;
    }

    // Who reads the region's file. Each has its own reader: a reader moves one position in the file as it reads, so
    // two threads sharing one (a search while a route is found, the places on the way while the route is found again)
    // read each other's bytes, which failed the search or left its thread stuck, and the search then answered no more.
    private static final int READ_SEARCH = 0;
    private static final int READ_ROUTE = 1;
    private static final int READ_ALONG = 2;
    private static final BinaryMapIndexReader[] readerCopies = new BinaryMapIndexReader[3];

    /** The search's reader of the region's file, opened on first use; null without the file. */
    private static BinaryMapIndexReader reader() throws Exception {
        return reader(READ_SEARCH);
    }

    /**
     * The reader of the region's file for one thread: the search's opens the file and reads its index; the others
     * share that index (OsmAnd's copy of a reader) over their own handle of the file.
     */
    private static synchronized BinaryMapIndexReader reader(int user) throws Exception {
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
        if (user == READ_SEARCH) {
            return reader;
        }
        if (readerCopies[user] == null) {
            readerCopies[user] = new BinaryMapIndexReader(new RandomAccessFile(file, "r"), reader);
        }
        return readerCopies[user];
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
            final long started = android.os.SystemClock.elapsedRealtime();
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
                                result.location.getLatitude(), result.location.getLongitude(), kindOf(result), typeOf(search, result)));
                        if (places.size() >= MAX_SEARCH_RESULTS) break;
                    }
                    // Nearest first from where the search was made (the user's last position), then the town named as
                    // typed above them all.
                    java.util.Collections.sort(places, (a, b) -> Double.compare(
                            MapEngine.distance(latitude, longitude, a.latitude, a.longitude),
                            MapEngine.distance(latitude, longitude, b.latitude, b.longitude)));
                    promoteMatchingTowns(places, query);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                android.util.Log.d("PhogramSearch", "failed: " + e);
            }
            android.util.Log.d("PhogramSearch", places.size() + " places in " + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && request == searchRequest) {
                    callback.onResults(query, places);
                }
            });
        });
    }

    /**
     * OsmAnd sorts by distance, so "Mosul" typed in Basra put a nearby restaurant of that name above the city. A town
     * or city whose name is what was typed comes first, as people mean the place itself; the rest keep OsmAnd's order.
     */
    private static void promoteMatchingTowns(List<MapEngine.Place> places, String query) {
        final String typed = normalize(query);
        if (typed.isEmpty()) return;
        final List<MapEngine.Place> towns = new ArrayList<>();
        for (int i = places.size() - 1; i >= 0; i--) {
            final MapEngine.Place place = places.get(i);
            if (place.kind == MapEngine.KIND_CITY && normalize(place.name).equals(typed)) {
                towns.add(0, places.remove(i));
            }
        }
        places.addAll(0, towns);
    }

    /** Lower case without Arabic diacritics, the Arabic definite article or spaces at the ends, for comparing names. */
    private static String normalize(String text) {
        if (text == null) return "";
        String value = text.trim().toLowerCase().replaceAll("[\\u064B-\\u0652\\u0640]", "")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي');
        if (value.startsWith("ال") && value.length() > 3) {
            value = value.substring(2);
        }
        return value;
    }

    /** The kind of a found place, from OsmAnd's place type (amenity subtype and category) or its address level. */
    private static int kindOf(SearchResult result) {
        if (result.objectType == null) return MapEngine.KIND_OTHER;
        switch (result.objectType) {
            case CITY:
            case VILLAGE:
            case BOUNDARY:
                return MapEngine.KIND_CITY;
            case STREET:
            case HOUSE:
            case STREET_INTERSECTION:
                return MapEngine.KIND_STREET;
            default:
                break;
        }
        if (!(result.object instanceof net.osmand.data.Amenity)) return MapEngine.KIND_OTHER;
        final net.osmand.data.Amenity amenity = (net.osmand.data.Amenity) result.object;
        final String subtype = amenity.getSubType() == null ? "" : amenity.getSubType();
        final String category = amenity.getType() == null ? "" : amenity.getType().getKeyName();
        return kindOfType(subtype, category);
    }

    /** The kind of a place from OsmAnd's type (its subtype, possibly several joined by ";") and category. */
    static int kindOfType(String subtype, String category) {
        if (subtype == null) subtype = "";
        if (category == null) category = "";
        // Towns come as places too, with types such as "city;residential;wiki_place".
        for (String part : subtype.split(";")) {
            switch (part) {
                case "city":
                case "town":
                case "village":
                case "hamlet":
                case "suburb":
                case "neighbourhood":
                case "quarter":
                    return MapEngine.KIND_CITY;
                default:
                    break;
            }
        }
        switch (subtype) {
            case "fuel":
            case "charging_station":
                return MapEngine.KIND_FUEL;
            case "pharmacy":
            case "chemist":
                return MapEngine.KIND_PHARMACY;
            case "hospital":
            case "clinic":
            case "doctors":
            case "dentist":
                return MapEngine.KIND_HEALTH;
            case "place_of_worship":
            case "mosque":
                return MapEngine.KIND_WORSHIP;
            case "school":
            case "university":
            case "college":
            case "kindergarten":
                return MapEngine.KIND_EDUCATION;
            case "bank":
            case "atm":
            case "bureau_de_change":
                return MapEngine.KIND_MONEY;
            case "hotel":
            case "motel":
            case "hostel":
            case "guest_house":
                return MapEngine.KIND_LODGING;
            case "parking":
                return MapEngine.KIND_PARKING;
            default:
                break;
        }
        if ("sustenance".equals(category)) return MapEngine.KIND_FOOD;
        if ("shop".equals(category)) return MapEngine.KIND_SHOP;
        if ("healthcare".equals(category)) return MapEngine.KIND_HEALTH;
        if ("education".equals(category)) return MapEngine.KIND_EDUCATION;
        if ("finance".equals(category)) return MapEngine.KIND_MONEY;
        if ("tourism".equals(category) && subtype.contains("hotel")) return MapEngine.KIND_LODGING;
        return MapEngine.KIND_OTHER;
    }

    /** The place's type in the app's language: OsmAnd's name for its amenity type, or empty for addresses. */
    private static String typeOf(SearchUICore search, SearchResult result) {
        try {
            if (result.object instanceof net.osmand.data.Amenity) {
                final net.osmand.data.Amenity amenity = (net.osmand.data.Amenity) result.object;
                if (amenity.getSubType() != null) {
                    // Only the first of several types ("city;residential;wiki_place").
                    return MapPoiTypes.getDefault().getPoiTranslation(amenity.getSubType().split(";")[0]);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "";
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

    /**
     * The ways by car between two points, the best first; an empty list when there is none. With alternatives, up to
     * three different routes from OsmAnd's highway hierarchy.
     */
    void route(double fromLatitude, double fromLongitude, double toLatitude, double toLongitude, boolean withAlternatives,
               Utilities.Callback<List<MapEngine.Route>> callback) {
        route(fromLatitude, fromLongitude, toLatitude, toLongitude, withAlternatives, null, callback);
    }

    /** The same, through the given stops in order; with stops there are no alternatives. */
    void route(double fromLatitude, double fromLongitude, double toLatitude, double toLongitude, boolean withAlternatives,
               List<MapEngine.Place> stops, Utilities.Callback<List<MapEngine.Route>> callback) {
        final List<LatLon> via = new ArrayList<>();
        if (stops != null) {
            for (MapEngine.Place stop : stops) {
                via.add(new LatLon(stop.latitude, stop.longitude));
            }
        }
        final int request = ++routeRequest;
        routeExecutor.execute(() -> {
            if (request != routeRequest) return;
            List<MapEngine.Route> routes = new ArrayList<>();
            try {
                final BinaryMapIndexReader file = reader(READ_ROUTE);
                if (file != null) {
                    routes = calculate(file, new LatLon(fromLatitude, fromLongitude), new LatLon(toLatitude, toLongitude), withAlternatives && via.isEmpty(), via);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final List<MapEngine.Route> result = routes;
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && request == routeRequest) {
                    callback.run(result);
                }
            });
        });
    }

    /**
     * Only the best route, for the time on a place's card. Estimates have their own turn, so a newer route does not
     * drop an estimate that the preview is about to reuse; a newer estimate drops an older one.
     */
    void estimate(double fromLatitude, double fromLongitude, double toLatitude, double toLongitude,
                  Utilities.Callback<List<MapEngine.Route>> callback) {
        final int request = ++estimateRequest;
        routeExecutor.execute(() -> {
            if (request != estimateRequest) return;
            List<MapEngine.Route> routes = new ArrayList<>();
            try {
                final BinaryMapIndexReader file = reader(READ_ROUTE);
                if (file != null) {
                    routes = calculate(file, new LatLon(fromLatitude, fromLongitude), new LatLon(toLatitude, toLongitude), false, new ArrayList<>());
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final List<MapEngine.Route> result = routes;
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && request == estimateRequest) {
                    callback.run(result);
                }
            });
        });
    }

    /**
     * Loads the highway hierarchy network ahead, with a short route in Baghdad, so the first real route does not wait
     * for it. Runs once per file, in the routes' turn.
     */
    static void warmUp() {
        if (warmedUp || installedFile() == null) return;
        warmedUp = true;
        routeExecutor.execute(() -> {
            final long started = android.os.SystemClock.elapsedRealtime();
            try {
                final BinaryMapIndexReader file = reader(READ_ROUTE);
                if (file != null) {
                    search(file, new LatLon(33.3152, 44.3661), new LatLon(33.3000, 44.3900), false, null);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            android.util.Log.d("PhogramRoute", "network loaded ahead in " + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
        });
    }

    /** At most this many routes are offered: the best and two others. */
    private static final int MAX_ROUTES = 3;

    private static List<MapEngine.Route> calculate(BinaryMapIndexReader file, LatLon start, LatLon end, boolean withAlternatives,
                                                  List<LatLon> via) throws Exception {
        final long started = android.os.SystemClock.elapsedRealtime();
        final List<MapEngine.Route> routes = new ArrayList<>();
        final RouteResultPreparation.RouteCalcResult result = search(file, start, end, withAlternatives, via);
        if (result == null || !result.isCorrect() || result.getList() == null || result.getList().isEmpty()) {
            android.util.Log.d("PhogramRoute", "no route after " + (android.os.SystemClock.elapsedRealtime() - started)
                    + " ms: " + (result != null ? result.getError() : "no result"));
            return routes;
        }
        final MapEngine.Route best = toRoute(result.getList());
        best.label = MapEngine.Route.LABEL_FASTEST;
        routes.add(best);
        if (withAlternatives) {
            final List<List<RouteSegmentResult>> alternatives = result.getAlternatives();
            if (alternatives != null) {
                for (List<RouteSegmentResult> alternative : alternatives) {
                    if (routes.size() >= MAX_ROUTES) break;
                    if (alternative == null || alternative.isEmpty()) continue;
                    final MapEngine.Route route = toRoute(alternative);
                    route.label = MapEngine.Route.LABEL_ALTERNATIVE;
                    addIfDifferent(routes, route);
                }
            }
            // The shortest way by distance is not searched any more: it cannot use the highway hierarchy, so across the
            // country it searched every road and took most of the time of a preview.
        }
        // How long the routes took to find, for the emulator check and for reports from phones.
        android.util.Log.d("PhogramRoute", String.format(java.util.Locale.US,
                "found in %d ms: %.1f km, %d min, %d points, %d turns, %d other routes, via %s",
                android.os.SystemClock.elapsedRealtime() - started, best.meters / 1000f, Math.round(best.seconds / 60f),
                best.points.size(), best.maneuvers.size(), routes.size() - 1, best.via));
        return routes;
    }

    /** One run of OsmAnd's car router, over its highway hierarchy. Runs on the routes' thread only. */
    private static RouteResultPreparation.RouteCalcResult search(BinaryMapIndexReader file, LatLon start, LatLon end,
                                                                 boolean alternatives, List<LatLon> via) throws Exception {
        synchronized (OsmAndOffline.class) {
            if (routingRules == null) {
                routingRules = RoutingConfiguration.getDefault();
            }
        }
        // The region's file holds what the route needs; OsmAnd's world regions are not read for missing maps.
        RoutePlannerFrontEnd.CALCULATE_MISSING_MAPS = false;
        final Map<String, String> params = new LinkedHashMap<>();
        final RoutingConfiguration config = routingRules.build("car",
                new RoutingConfiguration.RoutingMemoryLimits(ROUTING_MEMORY_MB, 0), params);
        final RoutePlannerFrontEnd planner = new RoutePlannerFrontEnd();
        // OsmAnd's highway hierarchy (its "HH" routing, run in Java): the map file carries a precomputed network of the
        // main roads, so a route across the country, such as Basra to Mosul, takes seconds instead of searching every
        // road on the way; the start and the end are then joined to that network on the detailed roads. Where the file
        // has no such network, OsmAnd falls back to its classic search over all roads.
        final net.osmand.router.HHRouteDataStructure.HHRoutingConfig hh = RoutePlannerFrontEnd.defaultHHConfig();
        if (alternatives) {
            hh.calcAlternative(MAX_ROUTES - 1);
        }
        planner.setHHRoutingConfig(hh);
        final RoutingContext context = planner.buildRoutingContext(config, null, new BinaryMapIndexReader[] { file },
                RoutePlannerFrontEnd.RouteCalculationMode.NORMAL);
        // The network of main roads, loaded once for this file and kept: loading it took seconds on every route.
        net.osmand.router.PhogramHHCache.use(hh, context, file);
        try {
            return planner.searchRoute(context, start, end, via == null || via.isEmpty() ? null : via);
        } finally {
            net.osmand.router.PhogramHHCache.keep(hh, file);
        }
    }

    /** Adds the route unless it is the same way as one already there: about as long, and through the same middle. */
    private static void addIfDifferent(List<MapEngine.Route> routes, MapEngine.Route route) {
        if (route.points.size() < 2) return;
        for (MapEngine.Route other : routes) {
            final float longer = Math.max(other.meters, route.meters);
            final boolean sameLength = longer <= 0 || Math.abs(other.meters - route.meters) / longer < 0.03f;
            final double[] a = middle(other);
            final double[] b = middle(route);
            final boolean sameMiddle = MapEngine.distance(a[0], a[1], b[0], b[1]) < 1500;
            if (sameLength && sameMiddle) return;
        }
        routes.add(route);
    }

    /** The point halfway along a route. */
    private static double[] middle(MapEngine.Route route) {
        final float half = route.along[route.along.length - 1] / 2;
        for (int i = 1; i < route.along.length; i++) {
            if (route.along[i] >= half) return route.points.get(i);
        }
        return route.points.get(route.points.size() - 1);
    }

    /** A route's points, turns and main road from OsmAnd's segments. */
    private static MapEngine.Route toRoute(List<RouteSegmentResult> segments) {
        final String language = language();
        final List<double[]> points = new ArrayList<>();
        final List<Integer> segmentStarts = new ArrayList<>();
        float meters = 0;
        float seconds = 0;
        final List<String> names = new ArrayList<>();
        final List<Float> limits = new ArrayList<>();
        // Warnings found on the points: their kind and their index in the route's points.
        final List<int[]> found = new ArrayList<>();
        final Map<String, Float> roadLengths = new HashMap<>();
        for (RouteSegmentResult segment : segments) {
            meters += segment.getDistance();
            seconds += segment.getSegmentTime();
            segmentStarts.add(Math.max(0, points.size() - 1));
            final int from = segment.getStartPointIndex();
            final int to = segment.getEndPointIndex();
            final int step = from <= to ? 1 : -1;
            for (int i = from; ; i += step) {
                final LatLon point = segment.getPoint(i);
                // Consecutive segments share their joining point; it is kept once.
                if (i != from || points.isEmpty()) {
                    points.add(new double[] { point.getLatitude(), point.getLongitude() });
                }
                final int warning = warningAt(segment.getObject(), i);
                if (warning >= 0) {
                    found.add(new int[] { warning, points.size() - 1 });
                }
                if (i == to) break;
            }
            final String road = roadName(segment, language);
            names.add(road);
            limits.add(speedLimit(segment));
            if (!road.isEmpty()) {
                final Float length = roadLengths.get(road);
                roadLengths.put(road, (length == null ? 0f : length) + segment.getDistance());
            }
        }
        // Distances along the route, the same way the route computes them, so the turns line up with the driver.
        final float[] along = new float[points.size()];
        for (int i = 1; i < along.length; i++) {
            along[i] = along[i - 1] + (float) MapEngine.distance(points.get(i - 1)[0], points.get(i - 1)[1], points.get(i)[0], points.get(i)[1]);
        }
        final List<MapEngine.Maneuver> maneuvers = new ArrayList<>();
        for (int s = 1; s < segments.size(); s++) {
            final RouteSegmentResult segment = segments.get(s);
            final int type = turnOf(segments.get(s - 1), segment);
            if (type < 0) continue;
            final int at = Math.min(segmentStarts.get(s), points.size() - 1);
            final TurnType turn = segment.getTurnType();
            final int exit = turn != null && turn.isRoundAbout() ? turn.getExitOut() : 0;
            final MapEngine.Maneuver maneuver = new MapEngine.Maneuver(type, exit, roadName(segment, language), at, along[at]);
            maneuver.angle = turnAngle(points, along, at);
            maneuvers.add(maneuver);
        }
        maneuvers.add(new MapEngine.Maneuver(MapEngine.Maneuver.ARRIVE, 0, "", points.size() - 1, along[points.size() - 1]));
        String via = "";
        float longest = 0;
        for (Map.Entry<String, Float> road : roadLengths.entrySet()) {
            if (road.getValue() > longest) {
                longest = road.getValue();
                via = road.getKey();
            }
        }
        final MapEngine.Route route = new MapEngine.Route(points, meters, seconds, maneuvers, via);
        route.roadStarts = new int[segmentStarts.size()];
        route.roadNames = new String[segmentStarts.size()];
        route.speedLimits = new float[segmentStarts.size()];
        for (int s = 0; s < segmentStarts.size(); s++) {
            route.roadStarts[s] = segmentStarts.get(s);
            route.roadNames[s] = names.get(s);
            route.speedLimits[s] = limits.get(s);
        }
        // One warning of a kind per 40 m: a junction often has its lights on several points.
        for (int[] warning : found) {
            final int at = Math.min(warning[1], points.size() - 1);
            boolean near = false;
            for (MapEngine.Warning other : route.warnings) {
                if (other.type == warning[0] && Math.abs(other.distance - along[at]) < 40) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                route.warnings.add(new MapEngine.Warning(warning[0], at, along[at], points.get(at)[0], points.get(at)[1]));
            }
        }
        return route;
    }

    /**
     * The real angle of the turn at a point: the direction of the road 25 m after it against the direction 25 m before
     * it, from -180 to 180, right positive. The arrows draw this angle, so they look like the junction itself.
     */
    private static float turnAngle(List<double[]> points, float[] along, int at) {
        int before = at;
        while (before > 0 && along[at] - along[before] < 25) before--;
        int after = at;
        while (after < points.size() - 1 && along[after] - along[at] < 25) after++;
        if (before == at || after == at) return Float.NaN;
        final double in = MapEngine.bearing(points.get(before)[0], points.get(before)[1], points.get(at)[0], points.get(at)[1]);
        final double out = MapEngine.bearing(points.get(at)[0], points.get(at)[1], points.get(after)[0], points.get(after)[1]);
        double angle = out - in;
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return (float) angle;
    }

    /** The road's speed limit in km/h from its maxspeed tag in the driving direction; 0 when the map does not say. */
    private static float speedLimit(RouteSegmentResult segment) {
        try {
            final float metersPerSecond = segment.getObject().getMaximumSpeed(segment.isForwardDirection());
            // OsmAnd's "none" (no limit) and the absence of a limit both mean no sign.
            if (metersPerSecond <= 0 || metersPerSecond >= RouteDataObject.NONE_MAX_SPEED) return 0;
            return Math.round(metersPerSecond * 3.6f);
        } catch (Exception e) {
            return 0;
        }
    }

    /** A warning on the road's point, from OpenStreetMap's tags there; -1 when there is none to tell of. */
    private static int warningAt(RouteDataObject road, int point) {
        try {
            final int[] types = road.getPointTypes(point);
            if (types == null) return -1;
            for (int type : types) {
                final net.osmand.binary.BinaryMapRouteReaderAdapter.RouteTypeRule rule = road.region.quickGetEncodingRule(type);
                if (rule == null) continue;
                final String tag = rule.getTag();
                final String value = rule.getValue();
                if ("highway".equals(tag)) {
                    if ("traffic_signals".equals(value)) return MapEngine.Warning.TRAFFIC_LIGHTS;
                    if ("speed_camera".equals(value)) return MapEngine.Warning.SPEED_CAMERA;
                    if ("stop".equals(value)) return MapEngine.Warning.STOP;
                } else if ("railway".equals(tag) && ("level_crossing".equals(value) || "crossing".equals(value))) {
                    return MapEngine.Warning.LEVEL_CROSSING;
                } else if ("traffic_calming".equals(tag)) {
                    return MapEngine.Warning.BUMP;
                }
            }
        } catch (Exception e) {
            // A point with rules this reader does not know is left without a warning.
        }
        return -1;
    }

    // Places along the route: OsmAnd's search of its place index around the route's line, for one kind of place.

    /** How far from the road a place may be to count as on the way, in meters. */
    private static final double ALONG_RADIUS = 400;
    /** The route is searched in stages of this length, nearest first, so the near places come at once. */
    private static final float ALONG_STAGE = 30_000;
    /** At most this many places over the whole route; the map shows as many as fit at its zoom. */
    private static final int MAX_ALONG = 400;
    private final ExecutorService alongExecutor = Executors.newSingleThreadExecutor();
    private volatile int alongRequest;

    /** What the places on the way report: all found so far, and whether the search is over. */
    interface AlongProgress {
        void onPlaces(List<MapEngine.AlongPlace> places, boolean finished);
    }

    /**
     * The places of this kind near the route ahead of the given point index, over the whole route to the destination,
     * nearest along the route first. The route is searched stage by stage from the driver on; after each stage the
     * callback gets all the places found so far, and the last call says the search is over.
     */
    void alongRoute(MapEngine.Route route, int fromIndex, int kind, AlongProgress callback) {
        final int request = ++alongRequest;
        alongExecutor.execute(() -> {
            final long started = android.os.SystemClock.elapsedRealtime();
            final List<MapEngine.AlongPlace> places = new ArrayList<>();
            final java.util.Set<String> seen = new java.util.HashSet<>();
            final int first = Math.max(0, Math.min(fromIndex, route.points.size() - 1));
            final float start = route.along[first];
            int index = first;
            try {
                final BinaryMapIndexReader file = reader(READ_ALONG);
                while (file != null && request == alongRequest && index < route.points.size() - 1 && places.size() < MAX_ALONG) {
                    // One stage of the road, a point every 200 m or so, which is what OsmAnd's search around a line needs.
                    final float stageEnd = route.along[index] + ALONG_STAGE;
                    final List<net.osmand.Location> line = new ArrayList<>();
                    final List<Integer> lineIndex = new ArrayList<>();
                    float last = -1;
                    int i = index;
                    for (; i < route.points.size(); i++) {
                        final boolean end = i == route.points.size() - 1 || route.along[i] >= stageEnd;
                        if (!end && last >= 0 && route.along[i] - last < 200) continue;
                        last = route.along[i];
                        line.add(new net.osmand.Location("route", route.points.get(i)[0], route.points.get(i)[1]));
                        lineIndex.add(i);
                        if (end) break;
                    }
                    index = Math.min(i, route.points.size() - 1);
                    if (line.size() >= 2) {
                        searchAlong(file, route, line, lineIndex, kind, start, request, seen, places);
                    }
                    java.util.Collections.sort(places, (a, b) -> Float.compare(a.ahead, b.ahead));
                    if (index >= route.points.size() - 1 || places.size() >= MAX_ALONG) break;
                    publishAlong(request, places, false, callback);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                android.util.Log.d("PhogramAlong", "failed: " + e);
            }
            while (places.size() > MAX_ALONG) {
                places.remove(places.size() - 1);
            }
            android.util.Log.d("PhogramAlong", "kind " + kind + ": " + places.size() + " places in "
                    + (android.os.SystemClock.elapsedRealtime() - started) + " ms, over "
                    + Math.round((route.along[index] - start) / 1000) + " km");
            publishAlong(request, places, true, callback);
        });
    }

    private void publishAlong(int request, List<MapEngine.AlongPlace> places, boolean finished, AlongProgress callback) {
        final List<MapEngine.AlongPlace> copy = new ArrayList<>(places);
        AndroidUtilities.runOnUIThread(() -> {
            if (!destroyed && request == alongRequest) {
                callback.onPlaces(copy, finished);
            }
        });
    }

    /** The places of the kind around one stage of the road, added to the list once each. */
    private void searchAlong(BinaryMapIndexReader file, MapEngine.Route route, List<net.osmand.Location> line,
                             List<Integer> lineIndex, int kind, float start, int request, java.util.Set<String> seen,
                             List<MapEngine.AlongPlace> places) throws java.io.IOException {
        final BinaryMapIndexReader.SearchPoiTypeFilter filter = new BinaryMapIndexReader.SearchPoiTypeFilter() {
            @Override
            public boolean accept(net.osmand.osm.PoiCategory type, String subcategory) {
                return kindOfType(subcategory, type == null ? "" : type.getKeyName()) == kind;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }
        };
        final List<net.osmand.data.Amenity> found = new ArrayList<>();
        final net.osmand.ResultMatcher<net.osmand.data.Amenity> matcher = new net.osmand.ResultMatcher<net.osmand.data.Amenity>() {
            @Override
            public boolean publish(net.osmand.data.Amenity amenity) {
                found.add(amenity);
                return true;
            }

            @Override
            public boolean isCancelled() {
                return request != alongRequest || found.size() > 600;
            }
        };
        file.searchPoi(BinaryMapIndexReader.buildSearchPoiRequest(line, ALONG_RADIUS, filter, matcher));
        final String language = language();
        for (net.osmand.data.Amenity amenity : found) {
            final LatLon at = amenity.getLocation();
            if (at == null) continue;
            // A place near the end of one stage is found again by the next one.
            final String key = Math.round(at.getLatitude() * 1e5) + "," + Math.round(at.getLongitude() * 1e5);
            if (!seen.add(key)) continue;
            // The nearest point of the line gives how far along the route the place is.
            double best = Double.MAX_VALUE;
            int bestIndex = -1;
            for (int i = 0; i < line.size(); i++) {
                final double d = MapEngine.distance(at.getLatitude(), at.getLongitude(), line.get(i).getLatitude(), line.get(i).getLongitude());
                if (d < best) {
                    best = d;
                    bestIndex = lineIndex.get(i);
                }
            }
            if (bestIndex < 0 || best > ALONG_RADIUS + 200) continue;
            String name = amenity.getName(language);
            if (TextUtils.isEmpty(name)) name = amenity.getName();
            final String type = amenity.getSubType() != null
                    ? MapPoiTypes.getDefault().getPoiTranslation(amenity.getSubType().split(";")[0]) : "";
            if (TextUtils.isEmpty(name)) name = type;
            places.add(new MapEngine.AlongPlace(new MapEngine.Place(name, "", at.getLatitude(), at.getLongitude(), kind, type),
                    Math.max(0, route.along[bestIndex] - start)));
        }
    }

    /** The road's name in the app's language, or its number (such as "1"), or empty. */
    private static String roadName(RouteSegmentResult segment, String language) {
        try {
            final RouteDataObject road = segment.getObject();
            String name = road.getName(language, false);
            if (TextUtils.isEmpty(name)) {
                name = road.getRef(language, false, segment.isForwardDirection());
            }
            return name == null ? "" : name.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The turn from one segment onto the next: OsmAnd's own turn where its route preparation set one, otherwise
     * from the change of direction between them. -1 when the driver just goes on.
     */
    private static int turnOf(RouteSegmentResult previous, RouteSegmentResult segment) {
        final TurnType turn = segment.getTurnType();
        if (turn != null) {
            switch (turn.getValue()) {
                case TurnType.TL: return MapEngine.Maneuver.LEFT;
                case TurnType.TSLL: return MapEngine.Maneuver.SLIGHT_LEFT;
                case TurnType.TSHL: return MapEngine.Maneuver.SHARP_LEFT;
                case TurnType.TR: return MapEngine.Maneuver.RIGHT;
                case TurnType.TSLR: return MapEngine.Maneuver.SLIGHT_RIGHT;
                case TurnType.TSHR: return MapEngine.Maneuver.SHARP_RIGHT;
                case TurnType.KL: return MapEngine.Maneuver.KEEP_LEFT;
                case TurnType.KR: return MapEngine.Maneuver.KEEP_RIGHT;
                case TurnType.TU:
                case TurnType.TRU: return MapEngine.Maneuver.U_TURN;
                case TurnType.RNDB:
                case TurnType.RNLB: return MapEngine.Maneuver.ROUNDABOUT;
                default: return -1;
            }
        }
        // Clockwise change of direction, from -180 to 180: positive turns right.
        float angle = segment.getBearingBegin() - previous.getBearingEnd();
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        final float size = Math.abs(angle);
        if (size < 30) return -1;
        final boolean right = angle > 0;
        if (size < 60) return right ? MapEngine.Maneuver.SLIGHT_RIGHT : MapEngine.Maneuver.SLIGHT_LEFT;
        if (size < 135) return right ? MapEngine.Maneuver.RIGHT : MapEngine.Maneuver.LEFT;
        if (size < 165) return right ? MapEngine.Maneuver.SHARP_RIGHT : MapEngine.Maneuver.SHARP_LEFT;
        return MapEngine.Maneuver.U_TURN;
    }

    void destroy() {
        destroyed = true;
        listeners.remove(listener);
        searchRequest++;
        routeRequest++;
        estimateRequest++;
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
