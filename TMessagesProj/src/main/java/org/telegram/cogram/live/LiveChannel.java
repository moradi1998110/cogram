package org.telegram.cogram.live;

/** A YouTube channel whose current live broadcast the app can play. */
public final class LiveChannel {

    /** YouTube's channel id, "UC" and 22 more characters. */
    public final String id;
    public final String name;
    /** Shipped with the app: always listed and never removable. */
    public final boolean builtIn;

    LiveChannel(String id, String name, boolean builtIn) {
        this.id = id;
        this.name = name;
        this.builtIn = builtIn;
    }

    public String embedUrl() {
        return "https://www.youtube.com/embed/live_stream?channel=" + id
                + "&autoplay=1&mute=1&playsinline=1&rel=0";
    }

    public String youtubeUrl() {
        return "https://www.youtube.com/channel/" + id + "/live";
    }
}
