package org.telegram.messenger.chromecast;

import android.net.Uri;

/** Phogram: a cover picture's address for a player's media, in place of the Chromecast library's class. */
public class WebImage {
    private final Uri url;

    public WebImage(Uri url) {
        this.url = url;
    }

    public Uri getUrl() {
        return url;
    }
}
