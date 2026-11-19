package org.telegram.cogram;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * The map files Phogram's maps draw from, downloaded after installation instead of shipping in the app.
 *
 * A server publishes catalog.json (the regions, their files, sizes and SHA-256 checksums) next to the files. By
 * default that is Phogram's public map repository on the internet; Settings > Data and Storage > Map server points
 * the app at an organization's own server that serves the same files. Files arrive through Android's download
 * manager, which resumes after a lost connection and goes on in the background, and are used only once their
 * checksum matches the catalog's.
 */
public final class MapData {

    /** Phogram's public map data: releases of github.com/moradi1998110/phogram-maps, generated from OpenStreetMap. */
    public static final String DEFAULT_SERVER = "https://github.com/moradi1998110/phogram-maps/releases/latest/download";

    private static final String PREFERENCES = "cogram_maps";
    private static final String KEY_SERVER = "server";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_DOWNLOAD_FILE = "download_file";
    private static final String CATALOG = "catalog.json";
    private static final int TIMEOUT_MS = 20_000;
    private static final int POLL_MS = 500;

    /** A file of a region, as the catalog lists it. */
    public static final class RegionFile {
        public final String region;
        public final String regionName;
        public final String name;
        public final long size;
        public final String sha256;

        RegionFile(String region, String regionName, String name, long size, String sha256) {
            this.region = region;
            this.regionName = regionName;
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final List<Runnable> listeners = new ArrayList<>();
    private static List<RegionFile> regions;
    private static boolean fetching;
    private static float progress;
    private static boolean polling;

    private MapData() {
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    // The server.

    public static String getCustomServer() {
        return preferences().getString(KEY_SERVER, "");
    }

    public static String getServer() {
        final String custom = getCustomServer();
        return TextUtils.isEmpty(custom) ? DEFAULT_SERVER : custom;
    }

    /** True for an address the app accepts: HTTPS only, so map files cannot be altered on the way. */
    public static boolean isValidServer(String address) {
        if (TextUtils.isEmpty(address)) {
            return true;
        }
        try {
            final Uri uri = Uri.parse(address.trim());
            return "https".equals(uri.getScheme()) && !TextUtils.isEmpty(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /** Sets the organization's server, or the default one for an empty address; the catalog is read again. */
    public static void setCustomServer(String address) {
        String value = address == null ? "" : address.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        preferences().edit().putString(KEY_SERVER, value).apply();
        synchronized (MapData.class) {
            regions = null;
        }
        catalogFile().delete();
        notifyListeners();
    }

    /** What the settings row shows: the organization's server's host, or that the maps come from the internet. */
    public static String describeServer() {
        final String custom = getCustomServer();
        if (TextUtils.isEmpty(custom)) {
            return LocaleController.getString(R.string.PhogramMapsServerDefault);
        }
        final String host = Uri.parse(custom).getHost();
        return host == null ? custom : host;
    }

    // Where the files live: the app's own external folder, removed with the app.

    static File mapsDir() {
        File base = ApplicationLoader.applicationContext.getExternalFilesDir(null);
        if (base == null) {
            base = ApplicationLoader.applicationContext.getFilesDir();
        }
        final File dir = new File(base, "maps");
        dir.mkdirs();
        return dir;
    }

    private static File catalogFile() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "maps-" + CATALOG);
    }

    /** The region's tiles once downloaded and checked, else null. */
    public static File installedTiles() {
        for (RegionFile file : regionFiles()) {
            final File local = new File(mapsDir(), file.name);
            if (local.exists() && local.length() == file.size) {
                return local;
            }
        }
        // The catalog may not be read yet; a file a check already accepted is still the region's tiles.
        final File[] files = mapsDir().listFiles((dir, name) -> name.endsWith(".pmtiles") && !name.contains("overview"));
        return files != null && files.length > 0 ? files[0] : null;
    }

    // The catalog.

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners() {
        AndroidUtilities.runOnUIThread(() -> {
            for (Runnable listener : new ArrayList<>(listeners)) {
                listener.run();
            }
        });
    }

    private static synchronized List<RegionFile> regionFiles() {
        if (regions == null) {
            regions = parse(read(catalogFile()));
        }
        return regions;
    }

    /** Reads the catalog from the server in the background, once per app run unless the server changes. */
    public static void refreshCatalog() {
        synchronized (MapData.class) {
            if (fetching) return;
            fetching = true;
        }
        final String server = getServer();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final String json = fetch(server + "/" + CATALOG);
                final List<RegionFile> parsed = parse(json);
                if (!parsed.isEmpty() && server.equals(getServer())) {
                    try (FileOutputStream out = new FileOutputStream(catalogFile())) {
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    synchronized (MapData.class) {
                        regions = parsed;
                    }
                    notifyListeners();
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                synchronized (MapData.class) {
                    fetching = false;
                }
            }
        });
    }

    private static String fetch(String address) throws Exception {
        final HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        try (InputStream in = connection.getInputStream()) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > 1 << 20) {
                    throw new IllegalStateException("catalog too large");
                }
            }
            return out.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    private static String read(File file) {
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            final byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                final int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** The catalog's tile files; names are checked so a file can never be written outside the maps folder. */
    private static List<RegionFile> parse(String json) {
        final List<RegionFile> result = new ArrayList<>();
        if (TextUtils.isEmpty(json)) return result;
        try {
            final JSONArray array = new JSONObject(json).getJSONArray("regions");
            final boolean arabic = "ar".equals(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            for (int i = 0; i < array.length(); i++) {
                final JSONObject region = array.getJSONObject(i);
                final JSONObject names = region.optJSONObject("name");
                final String regionName = names == null ? region.getString("id")
                        : names.optString(arabic ? "ar" : "en", region.getString("id"));
                final JSONArray files = region.getJSONArray("files");
                for (int j = 0; j < files.length(); j++) {
                    final JSONObject file = files.getJSONObject(j);
                    final String name = file.getString("name");
                    final String sha256 = file.getString("sha256").toLowerCase();
                    if (!"tiles".equals(file.optString("kind")) || !name.matches("[A-Za-z0-9_.-]+\\.pmtiles")
                            || !sha256.matches("[0-9a-f]{64}")) {
                        continue;
                    }
                    result.add(new RegionFile(region.getString("id"), regionName, name, file.getLong("size"), sha256));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    // The download.

    /** The region file to offer: the first the catalog lists that is not on the phone yet, or null. */
    public static RegionFile offer() {
        for (RegionFile file : regionFiles()) {
            final File local = new File(mapsDir(), file.name);
            if (!(local.exists() && local.length() == file.size)) {
                return file;
            }
        }
        return null;
    }

    public static boolean isDownloading() {
        return preferences().getLong(KEY_DOWNLOAD_ID, -1) != -1;
    }

    /** 0 to 100. */
    public static float getProgress() {
        return progress;
    }

    private static DownloadManager downloads() {
        return (DownloadManager) ApplicationLoader.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    public static void download(RegionFile file) {
        if (isDownloading()) return;
        final File part = new File(mapsDir(), file.name + ".part");
        part.delete();
        final DownloadManager.Request request = new DownloadManager.Request(Uri.parse(getServer() + "/" + file.name))
                .setTitle(LocaleController.formatString(R.string.PhogramMapsDownloadTitle, file.regionName))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(part));
        final long id = downloads().enqueue(request);
        preferences().edit().putLong(KEY_DOWNLOAD_ID, id).putString(KEY_DOWNLOAD_FILE, file.name).apply();
        progress = 0;
        notifyListeners();
        startPolling();
    }

    public static void cancel() {
        final long id = preferences().getLong(KEY_DOWNLOAD_ID, -1);
        if (id != -1) {
            downloads().remove(id);
        }
        finishDownload();
        notifyListeners();
    }

    private static void finishDownload() {
        final String name = preferences().getString(KEY_DOWNLOAD_FILE, null);
        preferences().edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_FILE).apply();
        if (name != null) {
            new File(mapsDir(), name + ".part").delete();
        }
        progress = 0;
    }

    /** Follows a running download, including one an earlier run of the app started. */
    public static void startPolling() {
        if (polling || !isDownloading()) return;
        polling = true;
        AndroidUtilities.runOnUIThread(MapData::poll, POLL_MS);
    }

    private static void poll() {
        final long id = preferences().getLong(KEY_DOWNLOAD_ID, -1);
        if (id == -1) {
            polling = false;
            return;
        }
        int status = DownloadManager.STATUS_FAILED;
        try (Cursor cursor = downloads().query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor != null && cursor.moveToFirst()) {
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                final long done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                final long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                progress = total > 0 ? Math.min(100, done * 100f / total) : 0;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            polling = false;
            verifyAndInstall();
        } else if (status == DownloadManager.STATUS_FAILED) {
            polling = false;
            finishDownload();
            notifyListeners();
        } else {
            notifyListeners();
            AndroidUtilities.runOnUIThread(MapData::poll, POLL_MS);
        }
    }

    /** The file is used only if it is exactly what the catalog describes; otherwise it is deleted. */
    private static void verifyAndInstall() {
        final String name = preferences().getString(KEY_DOWNLOAD_FILE, null);
        RegionFile expected = null;
        for (RegionFile file : regionFiles()) {
            if (file.name.equals(name)) expected = file;
        }
        final RegionFile file = expected;
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            if (file != null) {
                final File part = new File(mapsDir(), file.name + ".part");
                try {
                    ok = part.length() == file.size && file.sha256.equals(sha256(part))
                            && part.renameTo(new File(mapsDir(), file.name));
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (!ok) {
                    FileLog.e("cogram: a downloaded map file did not match the catalog and was deleted");
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                finishDownload();
                notifyListeners();
            });
        });
    }

    private static String sha256(File file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            final byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        final StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
