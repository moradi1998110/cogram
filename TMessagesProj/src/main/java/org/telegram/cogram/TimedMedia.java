package org.telegram.cogram;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Photos and videos seen once, or for some seconds, which XMPP has no standard for. They go only to
 * a contact whose every device announces {@link #FEATURE}, and only with OMEMO: before the file, a
 * text message names it and gives its time, inside the encrypted body, so the server never learns
 * the file is one. The receiving Phogram deletes the file, with the key to it, once it has been
 * seen; the server's copy stays encrypted, and the key that would open it is gone.
 * <pre>
 * caption, if any
 * [timed:FILE_ID|SECONDS]      SECONDS is {@link #VIEW_ONCE} for a file seen once
 * </pre>
 */
final class TimedMedia {

    static final String FEATURE = "urn:phogram:timed-media:0";
    // Telegram's time for a file that can be seen once.
    static final int VIEW_ONCE = 0x7FFFFFFF;
    private static final int MAX_SECONDS = 7 * 24 * 3600;
    // A file seen once goes once its viewer has surely closed; one with a time, just after that time.
    private static final long VIEW_ONCE_KEEP_MS = 2 * 60 * 1000L;
    private static final long TIMED_EXTRA_MS = 5 * 1000L;
    private static final Pattern MARK = Pattern.compile("\\[timed:([^|\\]\\s]{1,128})\\|(\\d{1,10})\\]");

    static final class Marker {
        final String file;
        final int seconds;
        final String caption;

        Marker(String file, int seconds, String caption) {
            this.file = file;
            this.seconds = seconds;
            this.caption = caption;
        }
    }

    private TimedMedia() {
    }

    static boolean isValid(int seconds) {
        return seconds == VIEW_ONCE || seconds > 0 && seconds <= MAX_SECONDS;
    }

    static String markerBody(String file, int seconds, String caption) {
        return (caption.isEmpty() ? "" : caption + "\n") + "[timed:" + file + "|" + seconds + "]";
    }

    static Marker parse(String body) {
        if (body == null || !body.contains("[timed:")) {
            return null;
        }
        int lastLine = body.lastIndexOf('\n');
        Matcher mark = MARK.matcher(body.substring(lastLine + 1));
        if (!mark.matches()) {
            return null;
        }
        long seconds = Long.parseLong(mark.group(2));
        if (seconds > Integer.MAX_VALUE || !isValid((int) seconds)) {
            return null;
        }
        return new Marker(mark.group(1), (int) seconds, lastLine < 0 ? "" : body.substring(0, lastLine));
    }

    static void apply(TLRPC.MessageMedia media, int seconds) {
        media.ttl_seconds = seconds;
        media.flags |= TLObject.FLAG_2;
    }

    static long keepAfterOpenedMs(int seconds) {
        return seconds == VIEW_ONCE ? VIEW_ONCE_KEEP_MS : seconds * 1000L + TIMED_EXTRA_MS;
    }
}
