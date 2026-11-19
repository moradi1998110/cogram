package org.telegram.ui;

import android.content.Context;
import android.media.AudioManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.Utilities;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phogram: Telegram's link between its players and a Chromecast, without Google's Cast library, which Phogram
 * does not ship. No cast session ever starts, so the players always play on the phone.
 */
public class CastSync {

    public static final int TYPE_PHOTOVIEWER = 0;
    public static final int TYPE_MUSIC = 1;

    public static int type;
    public static AtomicInteger pending;

    public static Context getContext() {
        Context context = LaunchActivity.instance;
        if (context == null) context = ApplicationLoader.applicationContext;
        return context;
    }

    public static void check(int type) {
        CastSync.type = type;
    }

    public static void stop() {
    }

    public static boolean isActive() {
        return false;
    }

    public static long getPosition() {
        return 0;
    }

    public static void seekTo(long position) {
    }

    public static void syncPosition(long position) {
    }

    public static void setVolume(float volume) {
    }

    public static float getVolume() {
        return getDeviceVolume();
    }

    public static boolean isPlaying() {
        return false;
    }

    public static void setPlaying(boolean play) {
    }

    public static void setSpeed(float speed) {
    }

    public static boolean isUpdatePending() {
        return false;
    }

    public static float getSpeed() {
        return 1.0f;
    }

    public static void doSyncVolume(boolean sync) {
    }

    public static void syncInterface() {
        if (type == TYPE_PHOTOVIEWER) {
            PhotoViewer.getInstance().syncCastedPlayer();
        } else if (type == TYPE_MUSIC) {
            MediaController.getInstance().syncCastedPlayer();
        }
    }

    public static float getDeviceVolume() {
        final Context context = getContext();
        if (context == null) return 0.0f;
        final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return 0.0f;

        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int minVolume = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            minVolume = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC);
        }

        return Utilities.clamp01((float) (currentVolume - minVolume) / (maxVolume - minVolume));
    }
}
