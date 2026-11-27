package org.telegram.cogram;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Dialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;

import java.util.List;
import java.util.Locale;

import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.Compatibility;

/**
 * What keeps Phogram receiving messages and calls with no screen open. It has no push service, only its own
 * connection to the server, so Android's battery savers and the phone maker's task killers must leave it
 * running. This reads how the phone treats the app and opens the system screens that change it.
 */
public final class BackgroundHealth {

    /** The phone makers whose own task killers stop apps that Android itself would leave running. */
    public static final int VENDOR_NONE = 0;
    public static final int VENDOR_HUAWEI = 1;
    public static final int VENDOR_XIAOMI = 2;
    public static final int VENDOR_OPPO = 3;
    public static final int VENDOR_VIVO = 4;
    public static final int VENDOR_SAMSUNG = 5;

    private static final String PREFERENCES = "phogram_background";
    private static final String KEY_ASKED_BATTERY = "askedBattery";
    private static final String KEY_LAST_STOP = "lastStop";
    private static final String KEY_EXITS_SEEN_UNTIL = "exitsSeenUntil";

    /** Telegram's incoming call channels all start with this, whatever their version and sound. */
    private static final String CALL_CHANNEL_PREFIX = "incoming_calls";

    /** The phone makers' screens that let an app start and keep running, newest first. */
    private static final String[][] HUAWEI_SCREENS = {
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
    };
    private static final String[][] XIAOMI_SCREENS = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
    };
    private static final String[][] OPPO_SCREENS = {
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
    };
    private static final String[][] VIVO_SCREENS = {
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
    };

    private BackgroundHealth() {
    }

    public static boolean notificationsAllowed(Context context) {
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /**
     * Whether an incoming call can ring with its full screen: its channel is not turned off and, from Android 14
     * on, the app may still show full screen notifications. A channel that no call has made yet counts as on.
     */
    public static boolean callScreenAllowed(Context context) {
        if (callChannelOff(context)) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager == null || manager.canUseFullScreenIntent();
        }
        return true;
    }

    private static boolean callChannelOff(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }
        for (NotificationChannel channel : manager.getNotificationChannels()) {
            if (channel.getId().startsWith(CALL_CHANNEL_PREFIX) && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return true;
            }
        }
        return false;
    }

    /** Whether the app is left out of battery optimization, which also lets it restart its connection unseen. */
    public static boolean batteryUnrestricted(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power == null || power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** Whether the user, or the system on their behalf, restricted the app's background activity. */
    public static boolean backgroundRestricted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        ActivityManager activities = context.getSystemService(ActivityManager.class);
        return activities != null && activities.isBackgroundRestricted();
    }

    public static int vendor() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        switch (maker) {
            case "huawei":
            case "honor":
                return VENDOR_HUAWEI;
            case "xiaomi":
            case "redmi":
            case "poco":
                return VENDOR_XIAOMI;
            case "oppo":
            case "realme":
            case "oneplus":
                return VENDOR_OPPO;
            case "vivo":
            case "iqoo":
                return VENDOR_VIVO;
            case "samsung":
                return VENDOR_SAMSUNG;
            default:
                return VENDOR_NONE;
        }
    }

    public static void openNotificationSettings(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            if (start(activity, intent)) {
                return;
            }
        }
        openAppDetails(activity);
    }

    public static void openCallScreenSettings(Activity activity) {
        if (!callChannelOff(activity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(activity));
            if (start(activity, intent)) {
                return;
            }
        }
        openNotificationSettings(activity);
    }

    /** Asks the system to leave the app out of battery optimization, or shows the list it is in once it is. */
    public static void openBatterySettings(Activity activity) {
        if (!batteryUnrestricted(activity)
                && start(activity, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(activity)))) {
            return;
        }
        if (start(activity, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            return;
        }
        openAppDetails(activity);
    }

    public static void openAppDetails(Activity activity) {
        start(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(activity)));
    }

    /**
     * Opens the phone maker's screen that lets the app start and run in the background. Only the maker's own
     * system app is opened, never one that took its package name; when none is there, the app's details are.
     */
    public static void openVendorSettings(Activity activity) {
        String[][] screens;
        switch (vendor()) {
            case VENDOR_HUAWEI:
                screens = HUAWEI_SCREENS;
                break;
            case VENDOR_XIAOMI:
                screens = XIAOMI_SCREENS;
                break;
            case VENDOR_OPPO:
                screens = OPPO_SCREENS;
                break;
            case VENDOR_VIVO:
                screens = VIVO_SCREENS;
                break;
            default:
                screens = new String[0][];
                break;
        }
        PackageManager packages = activity.getPackageManager();
        for (String[] screen : screens) {
            if (isSystemApp(packages, screen[0]) && start(activity, new Intent().setComponent(new ComponentName(screen[0], screen[1])))) {
                return;
            }
        }
        openAppDetails(activity);
    }

    private static boolean isSystemApp(PackageManager packages, String packageName) {
        try {
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static Uri packageUri(Context context) {
        return Uri.parse("package:" + context.getPackageName());
    }

    private static boolean start(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            // Phone makers move and lock these screens between versions; the caller tries the next one.
            FileLog.e(e, false);
            return false;
        }
    }

    /** Asked once after sign-in; the Background activity screen in Notifications settings stays open to it. */
    public static boolean shouldAskBattery(Context context) {
        return CogramGateway.ENABLED
                && hasEngineAccount(context)
                && !batteryUnrestricted(context)
                && !preferences(context).getBoolean(KEY_ASKED_BATTERY, false);
    }

    public static Dialog createBatteryDialog(Activity activity) {
        return new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.PhogramBackgroundBatteryTitle))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.PhogramBackgroundBatteryText)))
                .setTopAnimation(R.raw.permission_request_apk, AlertsCreator.PERMISSIONS_REQUEST_TOP_ICON_SIZE, false, Theme.getColor(Theme.key_dialogTopBackground))
                .setPositiveButton(LocaleController.getString(R.string.PermissionOpenSettings), (dialog, which) -> openBatterySettings(activity))
                .setNegativeButton(LocaleController.getString(R.string.PhogramBackgroundNotNow), null)
                .setOnDismissListener(dialog -> preferences(activity).edit().putBoolean(KEY_ASKED_BATTERY, true).apply())
                .create();
    }

    /**
     * Brings the engine's service back to the foreground. An app on screen may always do this, and it heals an
     * engine that a process start in the background left bound but not in the foreground, where Android freezes
     * it and cuts its network.
     */
    public static void keepEngineRunning(Context context) {
        if (hasEngineAccount(context)) {
            Compatibility.startService(context, new Intent(context, XmppConnectionService.class));
        }
    }

    private static boolean hasEngineAccount(Context context) {
        try {
            return Conversations.getInstance(context).hasEnabledAccount();
        } catch (RuntimeException e) {
            FileLog.e(e);
            return false;
        }
    }

    /** When the system or the phone maker last stopped the app while it was not on screen, or 0. */
    public static long lastStop(Context context) {
        return preferences(context).getLong(KEY_LAST_STOP, 0);
    }

    /**
     * Notes when the main process was last stopped behind the user's back, from the reasons Android keeps for
     * each process exit, so the settings screen can tell the user. Only times and reasons are kept, on this
     * device.
     */
    public static void recordStops(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            ActivityManager activities = context.getSystemService(ActivityManager.class);
            if (activities == null) {
                return;
            }
            List<ApplicationExitInfo> exits;
            try {
                exits = activities.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
            } catch (RuntimeException e) {
                FileLog.e(e);
                return;
            }
            SharedPreferences preferences = preferences(context);
            long seenUntil = preferences.getLong(KEY_EXITS_SEEN_UNTIL, 0);
            long newestSeen = seenUntil;
            long lastStop = preferences.getLong(KEY_LAST_STOP, 0);
            for (ApplicationExitInfo exit : exits) {
                if (exit.getTimestamp() <= seenUntil || !context.getPackageName().equals(exit.getProcessName())) {
                    continue;
                }
                newestSeen = Math.max(newestSeen, exit.getTimestamp());
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("phogram: process exit reason=" + exit.getReason() + " importance=" + exit.getImportance()
                            + " at " + exit.getTimestamp() + " (" + exit.getDescription() + ")");
                }
                if (stoppedInBackground(exit)) {
                    lastStop = Math.max(lastStop, exit.getTimestamp());
                }
            }
            if (newestSeen != seenUntil) {
                preferences.edit().putLong(KEY_EXITS_SEEN_UNTIL, newestSeen).putLong(KEY_LAST_STOP, lastStop).apply();
            }
        });
    }

    private static boolean stoppedInBackground(ApplicationExitInfo exit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || exit.getImportance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            return false;
        }
        switch (exit.getReason()) {
            case ApplicationExitInfo.REASON_USER_REQUESTED:
            case ApplicationExitInfo.REASON_OTHER:
            case ApplicationExitInfo.REASON_SIGNALED:
            case ApplicationExitInfo.REASON_LOW_MEMORY:
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
            case ApplicationExitInfo.REASON_FREEZER:
                return true;
            default:
                return false;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
