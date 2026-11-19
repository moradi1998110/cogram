package org.telegram.messenger.chromecast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phogram: what Telegram's players say about the media they play (title, artist, size), in place of the
 * Chromecast library's class of the same name, which Phogram does not ship.
 */
public class MediaMetadata {
    public static final int MEDIA_TYPE_GENERIC = 0;
    public static final int MEDIA_TYPE_MOVIE = 1;
    public static final int MEDIA_TYPE_MUSIC_TRACK = 3;
    public static final int MEDIA_TYPE_PHOTO = 4;

    public static final String KEY_TITLE = "title";
    public static final String KEY_SUBTITLE = "subtitle";
    public static final String KEY_ARTIST = "artist";
    public static final String KEY_ALBUM_TITLE = "albumTitle";
    public static final String KEY_ALBUM_ARTIST = "albumArtist";
    public static final String KEY_COMPOSER = "composer";
    public static final String KEY_DISC_NUMBER = "discNumber";
    public static final String KEY_TRACK_NUMBER = "trackNumber";
    public static final String KEY_WIDTH = "width";
    public static final String KEY_HEIGHT = "height";

    private final int mediaType;
    private final Map<String, Object> values = new HashMap<>();
    private final List<WebImage> images = new ArrayList<>();

    public MediaMetadata() {
        this(MEDIA_TYPE_GENERIC);
    }

    public MediaMetadata(int mediaType) {
        this.mediaType = mediaType;
    }

    public int getMediaType() {
        return mediaType;
    }

    public void putString(String key, String value) {
        values.put(key, value);
    }

    public String getString(String key) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : null;
    }

    public void putInt(String key, int value) {
        values.put(key, value);
    }

    public int getInt(String key) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : 0;
    }

    public void addImage(WebImage image) {
        images.add(image);
    }

    public List<WebImage> getImages() {
        return images;
    }
}
