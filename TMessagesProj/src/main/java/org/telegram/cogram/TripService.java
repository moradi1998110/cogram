package org.telegram.cogram;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

/**
 * The trip goes on with the app out of sight: as Telegram's live location, a foreground service keeps the GPS and the
 * voice going, and its notification shows the next turn. On Android 16 it is a Live Update, the turn's distance in a
 * chip on the status bar; before, it takes the green of Telegram's call bar, as the trip's strip does.
 */
public class TripService extends Service {

    /** What the notification's buttons do, set by the trip's panel while a trip runs. */
    interface Actions {
        void endTrip();
        void toggleVoice();
    }

    private static final int NOTIFICATION_ID = 41;
    private static final String ACTION_END = "end";
    private static final String ACTION_VOICE = "voice";

    private static Actions actions;
    private static Notification notification;
    private static String shown;
    private static MapEngine.Maneuver iconManeuver;
    private static Bitmap icon;

    static void start(Actions tripActions) {
        actions = tripActions;
        shown = null;
        notification = build(LocaleController.getString(R.string.PhogramNavStarting), "", "", null);
        final Context context = ApplicationLoader.applicationContext;
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, new Intent(context, TripService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    static void stop() {
        actions = null;
        notification = null;
        icon = null;
        iconManeuver = null;
        final Context context = ApplicationLoader.applicationContext;
        context.stopService(new Intent(context, TripService.class));
    }

    /** The notification follows the trip's strip; it is posted again only when what it says changes. */
    static void update(MapEngine.NavigationState state) {
        if (actions == null) return;
        final String title;
        final String chip;
        if (state.rerouting || state.next == null) {
            title = LocaleController.getString(R.string.PhogramNavRerouting);
            chip = "";
        } else {
            chip = MapsFormat.distance(state.toNext);
            title = chip + " · " + MapKinds.describe(state.next);
        }
        final String text = LocaleController.formatString(R.string.PhogramNavArrivalLine,
                MapsFormat.arrival(state.remainingSeconds), MapsFormat.distance(state.remainingMeters));
        final String key = title + text + state.street + VoiceGuide.isMuted();
        if (key.equals(shown)) return;
        shown = key;
        notification = build(title, state.street.isEmpty() ? text : state.street + "\n" + text, chip, state.rerouting ? null : state.next);
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            FileLog.e(e);
        }
    }

    private static Notification build(String title, String text, String chip, MapEngine.Maneuver maneuver) {
        final Context context = ApplicationLoader.applicationContext;
        final Intent open = new Intent(context, LaunchActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        NotificationsController.checkOtherNotificationsChannel();
        final int green = Theme.getColor(Theme.key_returnToCallBackground);
        final boolean muted = VoiceGuide.isMuted();
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                .setSmallIcon(R.drawable.live_loc)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(green)
                .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(muted ? R.drawable.msg_unmute : R.drawable.msg_mute,
                        LocaleController.getString(muted ? R.string.PhogramNavVoice : R.string.PhogramNavVoiceOff), action(context, ACTION_VOICE))
                .addAction(R.drawable.msg_cancel, LocaleController.getString(R.string.PhogramNavEnd), action(context, ACTION_END));
        if (maneuver != null) {
            if (maneuver != iconManeuver) {
                iconManeuver = maneuver;
                icon = MapKinds.turnBitmap(context, maneuver, green, AndroidUtilities.dp(48));
            }
            builder.setLargeIcon(icon);
        }
        if (Build.VERSION.SDK_INT >= 36) {
            // A Live Update (Android 16): kept on top, with the distance to the turn in the status bar's chip.
            final Bundle extras = new Bundle();
            extras.putBoolean("android.requestPromotedOngoing", true);
            if (!chip.isEmpty()) extras.putString("android.shortCriticalText", chip);
            builder.addExtras(extras);
        } else {
            builder.setColorized(true);
        }
        return builder.build();
    }

    private static PendingIntent action(Context context, String name) {
        final Intent intent = new Intent(context, TripService.class).setAction(name);
        return PendingIntent.getService(context, name.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (notification == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        final String name = intent != null ? intent.getAction() : null;
        final Actions tripActions = actions;
        if (tripActions != null && name != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (ACTION_END.equals(name)) {
                    tripActions.endTrip();
                } else if (ACTION_VOICE.equals(name)) {
                    tripActions.toggleVoice();
                }
            });
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
