package org.telegram.messenger.chromecast;

import java.io.File;
import java.util.Objects;

/**
 * Phogram: Telegram's Chromecast controller without Google's Cast library, which Phogram does not ship. Nothing
 * is ever cast, so Telegram's players always play on the phone and no cast device is looked for.
 */
public class ChromecastController {

    private static ChromecastController instance;

    public static ChromecastController getInstance() {
        if (instance == null) {
            instance = new ChromecastController();
        }
        return instance;
    }

    private ChromecastController() {
    }

    public boolean isCasting() {
        return false;
    }

    public void setCurrentMediaAndCastIfNeeded(ChromecastMediaVariations newMedia) {
    }

    public String setCover(File file) {
        return null;
    }

    public boolean isPlaying(ChromecastMediaVariations media) {
        return false;
    }

    public static boolean eq(ChromecastMediaVariations a, ChromecastMediaVariations b) {
        return Objects.equals(a, b);
    }

    public static boolean eq(ChromecastMedia a, ChromecastMedia b) {
        return Objects.equals(a, b);
    }
}
