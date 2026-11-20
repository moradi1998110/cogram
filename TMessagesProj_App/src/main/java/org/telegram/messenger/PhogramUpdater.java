package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.regular.BuildConfig;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.web.HttpGetFileTask;
import org.telegram.ui.web.HttpGetTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Phogram's updates without a store, from the organization's GitLab: Telegram's own updater for its beta builds
 * (BetaUpdaterController), reading Phogram's update.json instead, with two additions:
 *
 *  - the downloaded file is installed only when its SHA-256 matches the one in update.json, so a broken or replaced
 *    file is never offered (Android itself also refuses an update not signed with Phogram's key);
 *  - a release can name the oldest version allowed to go on ("min_version_code"); an older device must update.
 *
 * update.json, written by the release workflow:
 * {
 *   "version": "0.0.24", "version_code": 80129, "file_url": "phogram-0.0.24.apk",
 *   "sha256": "...", "min_version_code": 0, "changelog": "...", "changelog_ar": "..."
 * }
 * file_url may be relative to update.json. Versions are compared by version_code, as Android does.
 */
public class PhogramUpdater {

    private static PhogramUpdater instance;

    public static PhogramUpdater getInstance() {
        if (instance == null) {
            instance = new PhogramUpdater();
        }
        return instance;
    }

    public static boolean isEnabled() {
        return !TextUtils.isEmpty(BuildConfig.UPDATE_URL) && BuildConfig.UPDATE_URL.startsWith("https://");
    }

    private static final long CHECK_INTERVAL = 1000L * 60 * 60 * 3; // 3 hours while the app is open
    private static final long CHECK_INTERVAL_PAUSED = 1000L * 60 * 60 * 24;

    private String version;
    private int versionCode;
    private String changelog;
    private String sha256;
    private int minVersionCode;
    private String fileUrl;
    private String path;
    private long lastCheck;
    private boolean checking;
    private boolean firstCheck = true;
    private final Runnable scheduledCheck = () -> checkForUpdate(false, null);

    private PhogramUpdater() {
        load();
    }

    private SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("phogram_update", Activity.MODE_PRIVATE);
    }

    private void load() {
        final SharedPreferences p = preferences();
        version = p.getString("version", null);
        versionCode = p.getInt("versionCode", 0);
        changelog = p.getString("changelog", null);
        sha256 = p.getString("sha256", null);
        minVersionCode = p.getInt("minVersionCode", 0);
        fileUrl = p.getString("fileUrl", null);
        path = p.getString("path", null);
        lastCheck = p.getLong("lastCheck", 0L);
        if (currentVersionCode() >= versionCode || !TextUtils.isEmpty(path) && !new File(path).exists()) {
            clear();
        }
    }

    private void save() {
        preferences().edit()
                .putString("version", version)
                .putInt("versionCode", versionCode)
                .putString("changelog", changelog)
                .putString("sha256", sha256)
                .putInt("minVersionCode", minVersionCode)
                .putString("fileUrl", fileUrl)
                .putString("path", path)
                .putLong("lastCheck", lastCheck)
                .apply();
    }

    private void clear() {
        deleteDownloaded();
        version = null;
        versionCode = 0;
        changelog = null;
        sha256 = null;
        minVersionCode = 0;
        fileUrl = null;
        save();
    }

    private void deleteDownloaded() {
        if (!TextUtils.isEmpty(path)) {
            try {
                new File(path).delete();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        path = null;
    }

    /** Asks GitLab for the latest release; on its own every few hours while the app is open, or when forced. */
    public void checkForUpdate(boolean force, Runnable whenDone) {
        if (!isEnabled() || checking) {
            if (whenDone != null) whenDone.run();
            return;
        }
        if (firstCheck) force = true;
        if (!force && System.currentTimeMillis() - lastCheck < (ApplicationLoader.mainInterfacePaused ? CHECK_INTERVAL_PAUSED : CHECK_INTERVAL)) {
            if (whenDone != null) whenDone.run();
            return;
        }
        checking = true;
        firstCheck = false;
        final String url = BuildConfig.UPDATE_URL;
        new HttpGetTask(text -> AndroidUtilities.runOnUIThread(() -> {
            checking = false;
            final int oldCode = versionCode;
            try {
                if (text == null) throw new IllegalStateException("no answer");
                final JSONObject json = new JSONObject(text);
                final int newCode = json.getInt("version_code");
                final String newSha = json.getString("sha256").trim().toLowerCase(Locale.US);
                if (newCode > currentVersionCode() && newSha.matches("[0-9a-f]{64}")) {
                    if (newCode != versionCode || !newSha.equals(sha256)) {
                        deleteDownloaded();
                    }
                    version = json.getString("version");
                    versionCode = newCode;
                    sha256 = newSha;
                    fileUrl = resolve(url, json.getString("file_url"));
                    minVersionCode = json.optInt("min_version_code", 0);
                    final String arabic = json.optString("changelog_ar", "");
                    final boolean inArabic = "ar".equals(LocaleController.getInstance().getCurrentLocale().getLanguage());
                    changelog = inArabic && !TextUtils.isEmpty(arabic) ? arabic : json.optString("changelog", null);
                } else {
                    clear();
                }
                lastCheck = System.currentTimeMillis();
                save();
            } catch (Exception e) {
                FileLog.e("phogram: update check failed at " + url, e);
            }
            AndroidUtilities.cancelRunOnUIThread(scheduledCheck);
            AndroidUtilities.runOnUIThread(scheduledCheck, CHECK_INTERVAL);
            if (versionCode != oldCode) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            }
            if (whenDone != null) {
                whenDone.run();
            } else if (versionCode != oldCode && !ApplicationLoader.mainInterfacePaused) {
                showPopup();
            }
        })).execute(url);
    }

    /** Telegram's update sheet for the pending release, if any. */
    public void showPopup() {
        final Context context = LaunchActivity.instance != null ? LaunchActivity.instance : null;
        final BetaUpdate update = getUpdate();
        if (context != null && update != null) {
            ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(context, update, UserConfig.selectedAccount);
        }
    }

    private static String resolve(String base, String link) {
        try {
            return new java.net.URL(new java.net.URL(base), link).toString();
        } catch (Exception e) {
            return link;
        }
    }

    public PhogramUpdate getUpdate() {
        if (version == null || versionCode == 0) return null;
        return new PhogramUpdate(version, versionCode, changelog, isMandatory());
    }

    /** This device's version is older than the oldest the latest release allows. */
    public boolean isMandatory() {
        return versionCode != 0 && minVersionCode > currentVersionCode();
    }

    // Downloading, then checking the file before it is offered for installing.

    private boolean downloading;
    private float progress;
    private HttpGetFileTask task;

    public void downloadUpdate() {
        if (downloading || getDownloadedFile() != null || TextUtils.isEmpty(fileUrl) || !fileUrl.startsWith("https://")) return;
        downloading = true;
        progress = 0;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
        final String expected = sha256;
        task = new HttpGetFileTask(file -> Utilities.globalQueue.postRunnable(() -> {
            // The file is hashed off the main thread; a file that does not match is thrown away.
            final boolean good = file != null && expected != null && expected.equals(sha256Of(file));
            if (file != null && !good) {
                FileLog.e("phogram: the downloaded update does not match its SHA-256");
                file.delete();
            }
            AndroidUtilities.runOnUIThread(() -> {
                downloading = false;
                if (good) {
                    deleteDownloaded();
                    path = file.getAbsolutePath();
                    progress = 1;
                    save();
                } else if (file != null) {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin,
                            org.telegram.ui.Components.Bulletin.TYPE_ERROR, LocaleController.getString(R.string.PhogramUpdateBadFile));
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            });
        }), value -> {
            progress = value;
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
        }).setOverrideExtension("apk");
        task.execute(fileUrl);
    }

    public void cancelDownloadingUpdate() {
        if (!downloading) return;
        if (task != null) task.cancel(false);
        downloading = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    public boolean isDownloading() {
        return downloading;
    }

    public float getDownloadingProgress() {
        return progress;
    }

    public File getDownloadedFile() {
        if (path == null) return null;
        final File file = new File(path);
        if (!file.exists()) {
            path = null;
            save();
            return null;
        }
        return file;
    }

    private static String sha256Of(File file) {
        try (InputStream in = new FileInputStream(file)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            final StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format(Locale.US, "%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static int currentVersionCode() {
        try {
            return ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }
}
