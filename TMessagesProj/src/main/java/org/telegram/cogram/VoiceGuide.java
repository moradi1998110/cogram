package org.telegram.cogram;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Spoken guidance while navigating, from phrases recorded ahead in each language (assets/phogram_voice/<language>/),
 * so it needs no speech engine and no internet. A sentence is phrases played one after another, such as "in 200
 * meters" then "turn right". Music and other sound are lowered while it speaks.
 *
 * When to speak: before each turn at a few distances (on fast roads 2 km, 800 m and 300 m; in town 400 m and 100 m)
 * and at the turn itself, adding the turn after it when it follows closely; the warnings within 300 m; going over the
 * speed limit (at most once a minute); a new route, a stop reached and the arrival.
 */
final class VoiceGuide {

    /** The guidance languages: Arabic (the default), English and Persian. Kurdish comes with its recordings. */
    static final String[] LANGUAGES = { "ar", "en", "fa" };
    private static final String PREFERENCES = "cogram_maps_voice";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_MUTED = "muted";

    private static final float FAST_KMH = 70;
    private static final float[] FAST_STAGES = { 2000, 800, 300 };
    private static final float[] TOWN_STAGES = { 400, 100 };
    private static final float NOW_FAST = 60;
    private static final float NOW_TOWN = 30;
    private static final float THEN_WITHIN = 150;
    private static final float WARNING_WITHIN = 300;
    private static final long SPEEDING_EVERY_MS = 60_000;

    private final Context context;
    private final AudioManager audio;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private MediaPlayer player;
    private AudioFocusRequest focusRequest;
    private boolean focused;

    private int turnIndex = -1;
    private int turnStage = -1;
    private boolean turnNowSaid;
    private boolean arrivingSaid;
    private boolean wasRerouting;
    private long lastSpeeding;
    private final Set<String> warned = new HashSet<>();

    VoiceGuide(Context context) {
        this.context = context.getApplicationContext();
        audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static String language() {
        final String language = preferences().getString(KEY_LANGUAGE, "ar");
        for (String known : LANGUAGES) {
            if (known.equals(language)) return language;
        }
        return "ar";
    }

    static void setLanguage(String language) {
        preferences().edit().putString(KEY_LANGUAGE, language).apply();
    }

    static boolean isMuted() {
        return preferences().getBoolean(KEY_MUTED, false);
    }

    void setMuted(boolean muted) {
        preferences().edit().putBoolean(KEY_MUTED, muted).apply();
        if (muted) {
            queue.clear();
            stopPlayer();
        }
    }

    // What to say.

    void onStart() {
        reset();
        say("start");
    }

    void onArrived() {
        queue.clear();
        stopPlayer();
        say("arrive");
    }

    void onNavigation(MapEngine.NavigationState state) {
        if (state.rerouting && !wasRerouting) {
            say("rerouting");
            turnIndex = -1;
        }
        wasRerouting = state.rerouting;
        if (state.rerouting) return;
        if (state.passedStop != null) {
            say("stop_reached");
        }
        announceTurn(state);
        if (state.warning != null && state.toWarning < WARNING_WITHIN) {
            final String key = state.warning.type + "@" + state.warning.pointIndex;
            if (warned.add(key)) {
                say(warningKey(state.warning.type));
            }
        }
        if (state.speedLimit > 0 && state.speed > state.speedLimit + 5) {
            final long now = SystemClock.elapsedRealtime();
            if (now - lastSpeeding > SPEEDING_EVERY_MS) {
                lastSpeeding = now;
                say("speeding");
            }
        }
    }

    private void announceTurn(MapEngine.NavigationState state) {
        final MapEngine.Maneuver next = state.next;
        if (next == null) return;
        if (next.type == MapEngine.Maneuver.ARRIVE) {
            if (!arrivingSaid && state.toNext < 500) {
                arrivingSaid = true;
                say("arrive_soon");
            }
            return;
        }
        if (next.pointIndex != turnIndex) {
            turnIndex = next.pointIndex;
            turnStage = -1;
            turnNowSaid = false;
        }
        final boolean fast = state.speed > FAST_KMH;
        final float[] stages = fast ? FAST_STAGES : TOWN_STAGES;
        if (!turnNowSaid && state.toNext <= (fast ? NOW_FAST : NOW_TOWN)) {
            turnNowSaid = true;
            turnStage = stages.length;
            final String turn = turnKey(next);
            if (state.then != null && state.then.type != MapEngine.Maneuver.ARRIVE && state.then.distance - next.distance < THEN_WITHIN) {
                say(turn, "then", turnKey(state.then));
            } else {
                say(turn);
            }
            return;
        }
        // The nearest stage the driver has passed and has not heard yet; the farther ones are skipped.
        int stage = -1;
        for (int i = 0; i < stages.length; i++) {
            if (state.toNext <= stages[i]) stage = i;
        }
        if (stage > turnStage && state.toNext > (fast ? NOW_FAST : NOW_TOWN)) {
            turnStage = stage;
            say(distanceKey(state.toNext), turnKey(next));
        }
    }

    private void reset() {
        turnIndex = -1;
        turnStage = -1;
        turnNowSaid = false;
        arrivingSaid = false;
        wasRerouting = false;
        warned.clear();
    }

    static String turnKey(MapEngine.Maneuver maneuver) {
        switch (maneuver.type) {
            case MapEngine.Maneuver.LEFT: return "turn_left";
            case MapEngine.Maneuver.RIGHT: return "turn_right";
            case MapEngine.Maneuver.SLIGHT_LEFT: return "slight_left";
            case MapEngine.Maneuver.SLIGHT_RIGHT: return "slight_right";
            case MapEngine.Maneuver.SHARP_LEFT: return "sharp_left";
            case MapEngine.Maneuver.SHARP_RIGHT: return "sharp_right";
            case MapEngine.Maneuver.KEEP_LEFT: return "keep_left";
            case MapEngine.Maneuver.KEEP_RIGHT: return "keep_right";
            case MapEngine.Maneuver.U_TURN: return "uturn";
            case MapEngine.Maneuver.ROUNDABOUT:
                return maneuver.exit >= 1 && maneuver.exit <= 6 ? "exit_" + maneuver.exit : "roundabout";
            case MapEngine.Maneuver.ARRIVE: return "arrive";
            default: return "straight";
        }
    }

    /** The recorded distance at or just under the real one, so the driver never hears a turn later than it is. */
    static String distanceKey(float meters) {
        if (meters >= 5000) return "in_5km";
        if (meters >= 3000) return "in_3km";
        if (meters >= 2000) return "in_2km";
        if (meters >= 1500) return "in_1_5km";
        if (meters >= 1000) return "in_1km";
        if (meters >= 800) return "in_800";
        if (meters >= 500) return "in_500";
        if (meters >= 400) return "in_400";
        if (meters >= 300) return "in_300";
        if (meters >= 200) return "in_200";
        if (meters >= 150) return "in_150";
        if (meters >= 100) return "in_100";
        return "in_50";
    }

    static String warningKey(int type) {
        switch (type) {
            case MapEngine.Warning.TRAFFIC_LIGHTS: return "warn_lights";
            case MapEngine.Warning.LEVEL_CROSSING: return "warn_railway";
            case MapEngine.Warning.SPEED_CAMERA: return "warn_camera";
            case MapEngine.Warning.STOP: return "warn_stop";
            case MapEngine.Warning.PEDESTRIANS: return "warn_pedestrians";
            default: return "warn_bump";
        }
    }

    // Playing: one phrase after another, the guidance's audio usage, other sound lowered meanwhile.

    void say(String... keys) {
        if (isMuted()) return;
        for (String key : keys) {
            queue.add(key);
        }
        if (player == null) {
            playNext();
        }
    }

    private void playNext() {
        final String key = queue.poll();
        if (key == null) {
            abandonFocus();
            return;
        }
        requestFocus();
        try (AssetFileDescriptor file = context.getAssets().openFd("phogram_voice/" + language() + "/" + key + ".ogg")) {
            final MediaPlayer next = new MediaPlayer();
            next.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            next.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
            next.setOnCompletionListener(done -> {
                done.release();
                if (player == done) player = null;
                playNext();
            });
            next.setOnErrorListener((failed, what, extra) -> {
                failed.release();
                if (player == failed) player = null;
                playNext();
                return true;
            });
            next.prepare();
            player = next;
            next.start();
        } catch (Exception e) {
            // A phrase missing in this language is skipped.
            FileLog.e(e);
            player = null;
            playNext();
        }
    }

    private void stopPlayer() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignore) {
            }
            player.release();
            player = null;
        }
        abandonFocus();
    }

    @SuppressWarnings("deprecation")
    private void requestFocus() {
        if (focused || audio == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .build();
            audio.requestAudioFocus(focusRequest);
        } else {
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
        focused = true;
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (!focused || audio == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(null);
        }
        focused = false;
    }

    void release() {
        queue.clear();
        stopPlayer();
    }
}
