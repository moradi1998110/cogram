package org.telegram.cogram.live;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the live screen reads from YouTube's public pages, both by their canonical address:
 * <ul>
 * <li>the video id of a channel's broadcast right now. YouTube's embed by channel ("live_stream?channel=") now
 * shows "video unavailable", while the same broadcast embeds by its video id; a channel's /live page names that
 * video, and names the channel itself when it is not live;</li>
 * <li>the channel id behind a handle link such as youtube.com/@name, which is what YouTube's Share gives.</li>
 * </ul>
 */
final class LiveStreamResolver {

    private static final Pattern CANONICAL_VIDEO =
            Pattern.compile("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})\"");
    private static final Pattern CANONICAL_CHANNEL =
            Pattern.compile("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})\"");
    private static final Pattern HANDLE = Pattern.compile("(?:^|youtube\\.com/)@([A-Za-z0-9._-]{3,30})");

    private LiveStreamResolver() {
    }

    /** {@code done} runs on the UI thread with the video id, or null when the channel is not live or unreachable. */
    static void resolve(LiveChannel channel, Utilities.Callback<String> done) {
        Utilities.globalQueue.postRunnable(() -> {
            final String videoId = fetch(channel.youtubeUrl(), CANONICAL_VIDEO);
            AndroidUtilities.runOnUIThread(() -> done.run(videoId));
        });
    }

    /** A handle such as @name or youtube.com/@name in what the user pasted; null when there is none. */
    static String parseHandle(String input) {
        final Matcher matcher = HANDLE.matcher(input.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    /** {@code done} runs on the UI thread with the channel id of {@code handle}, or null when there is no such channel. */
    static void resolveHandle(String handle, Utilities.Callback<String> done) {
        Utilities.globalQueue.postRunnable(() -> {
            final String channelId = fetch("https://www.youtube.com/@" + handle, CANONICAL_CHANNEL);
            AndroidUtilities.runOnUIThread(() -> done.run(channelId));
        });
    }

    private static String fetch(String url, Pattern canonical) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36");
            connection.setRequestProperty("Accept-Language", "en");
            // The canonical link is in the page's head: read until it, not the whole page.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final Matcher matcher = canonical.matcher(line);
                    if (matcher.find()) return matcher.group(1);
                    if (line.contains("</head>")) break;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (connection != null) connection.disconnect();
        }
        return null;
    }
}
