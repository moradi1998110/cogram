package org.telegram.cogram;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The weather a trip will meet: ECMWF's forecast (CC BY 4.0, (c) ECMWF) at each point of the route at the time the
 * driver gets there, not the weather of now.
 *
 * The forecast comes as one-degree tiles over Iraq, beside the app's updates on the organization's GitLab
 * (.github/workflows/weather.yml). For a trip only the tiles its route crosses are fetched, checked against the
 * SHA-256 of the index and kept in the app's private storage until the trip ends, when they are deleted; anything
 * left over from a trip cut short goes after a day. The route itself never leaves the phone: a tile is a square
 * every driver crossing it asks for.
 */
final class RouteWeather {

    /** Nothing worth a word, something to know, something to take care with, a danger. */
    static final int LEVEL_NONE = 0;
    static final int LEVEL_INFO = 1;
    static final int LEVEL_CAUTION = 2;
    static final int LEVEL_DANGER = 3;

    static final int CLEAR = 0;
    static final int CLOUDY = 1;
    static final int RAIN = 2;
    static final int HEAVY_RAIN = 3;
    static final int STORM = 4;
    static final int SNOW = 5;
    static final int ICE = 6;
    static final int FOG = 7;
    static final int WIND = 8;
    static final int DUST = 9;
    static final int HEAT = 10;

    /** The weather at one point of the route, when the driver is there. */
    static final class Point {
        double latitude;
        double longitude;
        /** Meters from the route's start. */
        float along;
        /** When the driver is there, in milliseconds. */
        long time;
        int temperature;
        int wind;
        int gust;
        /** Millimetres in the three hours around then. */
        float rain;
        int precipitationType;
        int cloud;
        int cape;
        int fog;
        int dryness;
        /** What matters most there, and how much. */
        int condition;
        int level;
    }

    static final class Forecast {
        final List<Point> points = new ArrayList<>();
        /** When the forecast was made (its run), in milliseconds. */
        long run;
        /** The route's points past the forecast's last hour, or in no tile, are left out. */
        boolean partial;
    }

    interface Callback {
        void onForecast(Forecast forecast);
    }

    /** Points along the route this far apart; the map shows those that fit and the start of every hazard. */
    private static final float SAMPLE_METERS = 5000;
    private static final long INDEX_FRESH_MS = 30 * 60 * 1000L;
    private static final long LEFTOVER_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_INDEX_BYTES = 256 * 1024;
    private static final int MAX_TILE_BYTES = 512 * 1024;

    private final File dir = new File(ApplicationLoader.getFilesDirFixed(), "phogram_weather");
    private final Map<String, Tile> tiles = new HashMap<>();
    private JSONObject index;
    private String indexUrl;
    private long indexFetched;
    /** Each request's number; an answer to an older one is dropped. */
    private int generation;

    RouteWeather() {
        Utilities.globalQueue.postRunnable(this::deleteLeftovers);
    }

    static boolean isAvailable() {
        final ApplicationLoader loader = ApplicationLoader.applicationLoaderInstance;
        final String url = loader != null ? loader.weatherUrl() : "";
        return !TextUtils.isEmpty(url) && url.startsWith("https://");
    }

    /**
     * The weather from the given distance along the route to its end, for a driver who is at that distance at
     * {@code startMs}. The answer comes on the UI thread, or not at all when there is none (no network, no tiles,
     * a route outside Iraq) or a newer request came since.
     */
    void forRoute(MapEngine.Route route, float fromAlong, long startMs, Callback callback) {
        if (!isAvailable() || route == null || route.points.size() < 2) return;
        final int request = ++generation;
        final String url = ApplicationLoader.applicationLoaderInstance.weatherUrl();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final Forecast forecast = compute(url, route, fromAlong, startMs);
                if (forecast != null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (request == generation) callback.onForecast(forecast);
                    });
                }
            } catch (Exception e) {
                FileLog.e("phogram: route weather", e);
            }
        });
    }

    /** The trip is over: drops what was asked and deletes every tile kept for it. */
    void clear() {
        generation++;
        Utilities.globalQueue.postRunnable(() -> {
            tiles.clear();
            index = null;
            indexFetched = 0;
            deleteAll(null);
        });
    }

    // On the queue's thread from here on.

    private Forecast compute(String url, MapEngine.Route route, float fromAlong, long startMs) throws Exception {
        final float length = route.along[route.along.length - 1];
        if (length <= 0) return null;
        // The driver's average pace on this route gives the time at each point; the route's own estimate already
        // allows for its roads.
        final float secondsPerMeter = route.seconds / length;
        final List<float[]> samples = new ArrayList<>();
        int i = 0;
        for (float along = Math.max(0, fromAlong); ; along += SAMPLE_METERS) {
            if (along > length) along = length;
            while (i < route.along.length - 2 && route.along[i + 1] < along) i++;
            final float span = route.along[i + 1] - route.along[i];
            final float f = span > 0 ? Math.max(0, Math.min(1, (along - route.along[i]) / span)) : 0;
            final double[] a = route.points.get(i);
            final double[] b = route.points.get(i + 1);
            samples.add(new float[] { (float) (a[0] + (b[0] - a[0]) * f), (float) (a[1] + (b[1] - a[1]) * f), along });
            if (along >= length) break;
        }
        if (!ensureIndex(url)) {
            if (org.telegram.messenger.BuildVars.DEBUG_VERSION) android.util.Log.d("PhogramWeather", "no index at " + url);
            return null;
        }
        final Set<String> keys = new LinkedHashSet<>();
        for (float[] s : samples) keys.add(key(s[0], s[1]));
        for (String key : keys) ensureTile(url, key);
        final Forecast forecast = new Forecast();
        forecast.run = parseRun(index.optString("run"));
        for (float[] s : samples) {
            final Tile tile = tiles.get(key(s[0], s[1]));
            final long time = startMs + (long) ((s[2] - fromAlong) * secondsPerMeter * 1000);
            if (tile == null || !tile.covers(time)) {
                forecast.partial = true;
                continue;
            }
            final Point point = tile.at(s[0], s[1], time);
            point.along = s[2];
            classify(point);
            forecast.points.add(point);
        }
        // Test builds tell the emulator check what came.
        if (org.telegram.messenger.BuildVars.DEBUG_VERSION) {
            final Point worst = forecast.points.isEmpty() ? null : worst(forecast);
            android.util.Log.d("PhogramWeather", "run " + index.optString("version") + ", tiles " + keys.size()
                    + " (" + tiles.size() + " loaded), points " + forecast.points.size() + "/" + samples.size()
                    + (worst != null ? ", worst " + worst.condition + " level " + worst.level : ", calm")
                    + (forecast.points.isEmpty() ? "" : ", first " + forecast.points.get(0).temperature + "C"));
        }
        return forecast.points.isEmpty() ? null : forecast;
    }

    private static String key(double latitude, double longitude) {
        return (int) Math.floor(latitude) + "_" + (int) Math.floor(longitude);
    }

    private boolean ensureIndex(String url) throws Exception {
        if (index != null && url.equals(indexUrl) && System.currentTimeMillis() - indexFetched < INDEX_FRESH_MS) return true;
        final byte[] bytes = fetch(url, MAX_INDEX_BYTES);
        if (bytes == null) return index != null;
        final JSONObject fresh = new JSONObject(new String(bytes, "UTF-8"));
        if (fresh.optInt("v") != 1 || !fresh.has("tiles")) return index != null;
        if (index == null || !fresh.optString("version").equals(index.optString("version"))) {
            // A newer run: the tiles kept from the older one go.
            tiles.clear();
            deleteAll(fresh.optString("version"));
        }
        index = fresh;
        indexUrl = url;
        indexFetched = System.currentTimeMillis();
        return true;
    }

    private void ensureTile(String url, String key) throws Exception {
        if (tiles.containsKey(key)) return;
        final JSONObject entry = index.getJSONObject("tiles").optJSONObject(key);
        if (entry == null) return;
        final String sha = entry.optString("sha256").toLowerCase(Locale.US);
        if (!sha.matches("[0-9a-f]{64}")) return;
        final String version = index.optString("version");
        if (!version.matches("[0-9]{10}")) return;
        final File file = new File(dir, version + "_" + key + ".json.gz");
        byte[] data = file.isFile() ? readFile(file) : null;
        if (data == null || !sha.equals(sha256(data))) {
            final String tileUrl = URI.create(url).resolve("../" + version + "/w_" + key + ".json.gz").toString();
            data = fetch(tileUrl, MAX_TILE_BYTES);
            if (data == null || !sha.equals(sha256(data))) {
                FileLog.e("phogram: weather tile " + key + " missing or not the one the index names");
                return;
            }
            if (dir.isDirectory() || dir.mkdirs()) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
            }
        }
        final Tile tile = Tile.parse(gunzip(data));
        if (tile != null) tiles.put(key, tile);
    }

    private void deleteLeftovers() {
        final File[] files = dir.listFiles();
        if (files == null) return;
        final long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > LEFTOVER_MS) f.delete();
        }
    }

    /** Deletes the kept tiles, all of them, or those of every run but the one given. */
    private void deleteAll(String keepVersion) {
        final File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (keepVersion == null || !f.getName().startsWith(keepVersion + "_")) f.delete();
        }
    }

    /** Over HTTPS only, within the size given; null on any failure. */
    private static byte[] fetch(String address, int maxBytes) {
        HttpURLConnection connection = null;
        try {
            if (!address.startsWith("https://")) return null;
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection.getResponseCode() != 200) {
                FileLog.e("phogram: weather " + connection.getResponseCode() + " at " + address);
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                return readLimited(in, maxBytes);
            }
        } catch (Exception e) {
            FileLog.e("phogram: weather fetch failed: " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > maxBytes) throw new IllegalStateException("too large");
        }
        return out.toByteArray();
    }

    private static byte[] readFile(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return readLimited(in, MAX_TILE_BYTES);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            return readLimited(in, 8 * MAX_TILE_BYTES);
        }
    }

    private static String sha256(byte[] data) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        final StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b));
        return hex.toString();
    }

    private static long parseRun(String run) {
        try {
            final java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return format.parse(run).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * What a driver needs to know at a point, the most serious first. The thresholds are those weather services warn
     * drivers at: gusts that push high vehicles from 60 km/h, a downpour from 4 mm in three hours, thunderstorm
     * energy from 1000 J/kg with rain; dust is wind over dry ground with no rain, the dust storms of Iraq's roads.
     */
    static void classify(Point p) {
        int condition = p.cloud >= 70 ? CLOUDY : CLEAR;
        int level = LEVEL_NONE;
        final boolean freezing = p.precipitationType == 3 || p.precipitationType == 8 || p.precipitationType == 12;
        final boolean snow = p.precipitationType == 5 || p.precipitationType == 6 || p.precipitationType == 7;
        if (p.rain >= 0.3f) {
            condition = RAIN;
            level = LEVEL_INFO;
        }
        if (p.rain >= 4) {
            condition = HEAVY_RAIN;
            level = p.rain >= 10 ? LEVEL_DANGER : LEVEL_CAUTION;
        }
        if (p.temperature >= 47 && level < LEVEL_CAUTION) {
            condition = HEAT;
            level = LEVEL_CAUTION;
        }
        if (p.gust >= 60 && level <= LEVEL_CAUTION) {
            condition = WIND;
            level = p.gust >= 80 ? LEVEL_DANGER : LEVEL_CAUTION;
        }
        if (p.gust >= 45 && p.rain < 0.2f && p.dryness >= 12 && level <= LEVEL_CAUTION) {
            condition = DUST;
            level = p.gust >= 65 ? LEVEL_DANGER : LEVEL_CAUTION;
        }
        if (p.fog >= 45 && level <= LEVEL_CAUTION) {
            condition = FOG;
            level = p.fog >= 70 ? LEVEL_DANGER : LEVEL_CAUTION;
        }
        if (snow && p.rain >= 0.3f) {
            condition = SNOW;
            level = Math.max(level, p.rain >= 3 ? LEVEL_DANGER : LEVEL_CAUTION);
        }
        if (p.cape >= 1000 && p.rain >= 1) {
            condition = STORM;
            level = Math.max(level, p.cape >= 2000 && p.rain >= 5 ? LEVEL_DANGER : LEVEL_CAUTION);
        }
        if (freezing || (p.temperature <= 0 && p.rain >= 0.3f && !snow)) {
            condition = ICE;
            level = LEVEL_DANGER;
        }
        p.condition = condition;
        p.level = level;
    }

    // What the page shows.

    /** Calm weather gets a bubble this far apart at most; every hazard gets one where it starts. */
    private static final float CALM_SPACING = 25000;

    /**
     * The points worth a bubble: each stretch of the same hazard once, at its worst point; calm weather now and then;
     * and the destination.
     */
    static List<Point> marks(Forecast forecast) {
        final List<Point> out = new ArrayList<>();
        Point group = null;
        Point lastCalm = null;
        for (Point p : forecast.points) {
            if (p.level >= LEVEL_CAUTION) {
                if (group != null && p.condition == group.condition) {
                    if (p.level > group.level) group = p;
                    continue;
                }
                if (group != null) out.add(group);
                group = p;
            } else {
                if (group != null) out.add(group);
                group = null;
                if (lastCalm == null || p.along - lastCalm.along >= CALM_SPACING) {
                    out.add(p);
                    lastCalm = p;
                }
            }
        }
        if (group != null) out.add(group);
        final Point last = forecast.points.get(forecast.points.size() - 1);
        if (!out.contains(last) && (group == null || group.condition != last.condition)) out.add(last);
        return out;
    }

    /** The most serious point ahead, the nearest among equals; null when nothing is worth a word. */
    static Point worst(Forecast forecast) {
        Point worst = null;
        for (Point p : forecast.points) {
            if (p.level >= LEVEL_INFO && (worst == null || p.level > worst.level)) worst = p;
        }
        return worst;
    }

    static String emoji(Point p) {
        switch (p.condition) {
            case RAIN: case HEAVY_RAIN: return "🌧️";
            case STORM: return "⛈️";
            case SNOW: return "🌨️";
            case ICE: return "🧊";
            case FOG: return "🌫️";
            case WIND: return "💨";
            case DUST: return "🌪️";
            case HEAT: return "🌡️";
            case CLOUDY: return "☁️";
        }
        final java.util.Calendar at = java.util.Calendar.getInstance();
        at.setTimeInMillis(p.time);
        final int hour = at.get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 19 || hour < 6 ? "🌙" : "☀️";
    }

    static String name(int condition) {
        switch (condition) {
            case RAIN: return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PhogramWeatherRain);
            case HEAVY_RAIN: return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PhogramWeatherHeavyRain);
            case STORM: return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PhogramWeatherStorm);
            case SNOW: return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PhogramWeatherSnow);
            case ICE: return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PhogramWeatherIce);
            case FOG: return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PhogramWeatherFog);
            case WIND: return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PhogramWeatherWind);
            case DUST: return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PhogramWeatherDust);
            case HEAT: return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PhogramWeatherHeat);
        }
        return "";
    }

    /** The bubble's first line: the sign and the one figure that matters there. */
    static String headline(Point p) {
        final String figure;
        switch (p.condition) {
            case RAIN: case HEAVY_RAIN:
                figure = org.telegram.messenger.LocaleController.formatString(org.telegram.messenger.R.string.PhogramWeatherMm,
                        p.rain >= 10 ? String.valueOf(Math.round(p.rain)) : String.format(Locale.US, "%.1f", p.rain));
                break;
            case WIND:
                figure = org.telegram.messenger.LocaleController.formatString(org.telegram.messenger.R.string.PhogramUnitKmh, p.gust);
                break;
            case STORM: case SNOW: case ICE: case FOG: case DUST:
                figure = name(p.condition);
                break;
            default:
                figure = p.temperature + "°";
                break;
        }
        return emoji(p) + " " + figure;
    }

    /** One tile: its points from south to north, west to east, at each valid time. */
    private static final class Tile {
        double south;
        double west;
        double resolution;
        int size;
        long[] times;
        final Map<String, int[][]> fields = new HashMap<>();

        static Tile parse(byte[] json) throws Exception {
            final JSONObject o = new JSONObject(new String(json, "UTF-8"));
            if (o.optInt("v") != 1) return null;
            final Tile t = new Tile();
            t.south = o.getDouble("lat");
            t.west = o.getDouble("lon");
            t.resolution = o.getDouble("res");
            t.size = o.getInt("n");
            final JSONArray times = o.getJSONArray("times");
            t.times = new long[times.length()];
            for (int i = 0; i < t.times.length; i++) t.times[i] = times.getLong(i) * 1000;
            final JSONObject f = o.getJSONObject("f");
            for (String name : new String[] { "t", "d", "w", "g", "r", "p", "c", "k", "f" }) {
                final JSONArray series = f.optJSONArray(name);
                if (series == null) continue;
                final int[][] values = new int[series.length()][];
                for (int s = 0; s < values.length; s++) {
                    final JSONArray grid = series.getJSONArray(s);
                    if (grid.length() != t.size * t.size) return null;
                    values[s] = new int[grid.length()];
                    for (int k = 0; k < values[s].length; k++) values[s][k] = grid.getInt(k);
                }
                if (values.length != t.times.length) return null;
                t.fields.put(name, values);
            }
            return t.times.length > 0 && t.fields.containsKey("t") ? t : null;
        }

        /** Each value is valid for the three hours ending at its time (the rain) or around it. */
        boolean covers(long time) {
            return time >= times[0] - 3 * 3600_000L && time <= times[times.length - 1] + 3600_000L;
        }

        Point at(double latitude, double longitude, long time) {
            final double y = Math.max(0, Math.min(size - 1, (latitude - south) / resolution));
            final double x = Math.max(0, Math.min(size - 1, (longitude - west) / resolution));
            int step = 0;
            while (step < times.length - 2 && times[step + 1] < time) step++;
            double ft = 0;
            if (times.length > 1) {
                ft = (double) (time - times[step]) / (times[step + 1] - times[step]);
                ft = Math.max(0, Math.min(1, ft));
            }
            final int nearestStep = ft < 0.5 || times.length == 1 ? step : step + 1;
            final Point p = new Point();
            p.latitude = latitude;
            p.longitude = longitude;
            p.time = time;
            p.temperature = (int) Math.round(value("t", x, y, step, ft));
            p.dryness = (int) Math.round(value("d", x, y, step, ft));
            p.wind = (int) Math.round(value("w", x, y, step, ft));
            p.gust = (int) Math.round(value("g", x, y, step, ft));
            p.rain = (float) value("r", x, y, step, ft) / 10f;
            p.cloud = (int) Math.round(value("c", x, y, step, ft));
            p.cape = (int) Math.round(value("k", x, y, step, ft)) * 10;
            p.fog = (int) Math.round(value("f", x, y, step, ft));
            final int[][] type = fields.get("p");
            p.precipitationType = type != null ? type[nearestStep][(int) Math.round(y) * size + (int) Math.round(x)] : 0;
            return p;
        }

        /** Between the four points around and the two times around. */
        private double value(String name, double x, double y, int step, double ft) {
            final int[][] grid = fields.get(name);
            if (grid == null) return 0;
            final double now = bilinear(grid[step], x, y);
            if (ft <= 0 || step + 1 >= grid.length) return now;
            return now + (bilinear(grid[step + 1], x, y) - now) * ft;
        }

        private double bilinear(int[] g, double x, double y) {
            final int x0 = (int) Math.floor(x);
            final int y0 = (int) Math.floor(y);
            final int x1 = Math.min(x0 + 1, size - 1);
            final int y1 = Math.min(y0 + 1, size - 1);
            final double fx = x - x0;
            final double fy = y - y0;
            final double bottom = g[y0 * size + x0] * (1 - fx) + g[y0 * size + x1] * fx;
            final double top = g[y1 * size + x0] * (1 - fx) + g[y1 * size + x1] * fx;
            return bottom * (1 - fy) + top * fy;
        }
    }
}
