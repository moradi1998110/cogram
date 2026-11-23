package org.telegram.cogram.live;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The channels of the live screen: the ones shipped with the app, then the ones this user added,
 * which stay on this device only.
 */
public final class LiveChannels {

    private static final String PREFERENCES = "phogram_live";
    private static final String KEY_CHANNELS = "channels";
    private static final Pattern CHANNEL_ID = Pattern.compile("(?:^|/channel/)(UC[A-Za-z0-9_-]{22})(?:$|[/?#])");

    private LiveChannels() {
    }

    public static List<LiveChannel> all() {
        final ArrayList<LiveChannel> list = new ArrayList<>();
        // Built in, named in the app's language: add more here.
        list.add(new LiveChannel("UCfiwzLy-8yKzIbsmZTzxDgw", LocaleController.getString(R.string.PhogramLiveAlJazeera), true));
        list.addAll(added());
        return list;
    }

    /** The channel id in a link like youtube.com/channel/UC… or in a bare id; null for anything else. */
    public static String parseId(String input) {
        final Matcher matcher = CHANNEL_ID.matcher(input.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    public static boolean contains(String id) {
        for (LiveChannel channel : all()) {
            if (channel.id.equals(id)) return true;
        }
        return false;
    }

    /** Adds the channel, or renames it when this user already added it. */
    public static void put(String id, String name) {
        final List<LiveChannel> list = added();
        int index = indexOf(list, id);
        final LiveChannel channel = new LiveChannel(id, name, false);
        if (index >= 0) {
            list.set(index, channel);
        } else {
            list.add(channel);
        }
        save(list);
    }

    public static void remove(String id) {
        final List<LiveChannel> list = added();
        final int index = indexOf(list, id);
        if (index >= 0) {
            list.remove(index);
            save(list);
        }
    }

    private static int indexOf(List<LiveChannel> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private static List<LiveChannel> added() {
        final ArrayList<LiveChannel> list = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(preferences().getString(KEY_CHANNELS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                final JSONObject item = array.getJSONObject(i);
                list.add(new LiveChannel(item.getString("id"), item.getString("name"), false));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return list;
    }

    private static void save(List<LiveChannel> list) {
        final JSONArray array = new JSONArray();
        try {
            for (LiveChannel channel : list) {
                array.put(new JSONObject().put("id", channel.id).put("name", channel.name));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        preferences().edit().putString(KEY_CHANNELS, array.toString()).apply();
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
