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
 * The video id of a channel's broadcast right now. YouTube's embed by channel ("live_stream?channel=") now shows
 * "video unavailable", while the same broadcast embeds by its video id; a channel's /live page names that video
 * as its canonical address, and names the channel itself when it is not live.
 */
final class LiveStreamResolver {

    private static final Pattern CANONICAL_VIDEO =
            Pattern.compile("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})\"");

    private LiveStreamResolver() {
    }

    /** {@code done} runs on the UI thread with the video id, or null when the channel is not live or unreachable. */
    static void resolve(LiveChannel channel, Utilities.Callback<String> done) {
        Utilities.globalQueue.postRunnable(() -> {
            final String videoId = fetch(channel.youtubeUrl());
            AndroidUtilities.runOnUIThread(() -> done.run(videoId));
        });
    }

    private static String fetch(String url) {
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
                    final Matcher matcher = CANONICAL_VIDEO.matcher(line);
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
