package org.telegram.cogram;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The reactions Telegram's reaction bar offers. Telegram's server sends each one with animations from its own
 * servers; Phogram reaches no such server, so the animations come with the app: Google's Noto Animated Emoji
 * (CC BY 4.0, assets/phogram_reactions), vector Lottie files Telegram plays as it plays its own. XMPP reactions
 * (XEP-0444) are plain emoji, so any of these reaches every XMPP app.
 *
 * Telegram draws a reaction's center icon at twice its box, because its own center icons keep the emoji in
 * the middle half of the frame. Noto's emoji fill the frame, so the center icon is the same animation scaled to
 * half around the middle; without that a reaction just given showed twice as large until the chat redrew it.
 */
final class ReactionCatalog {

    /** Telegram's usual reactions, in its order, each with its Noto animation; the ones Noto lacks are left out. */
    private static final String[][] REACTIONS = {
            {"👍", "1f44d"}, {"👎", "1f44e"}, {"❤", "2764_fe0f"}, {"🔥", "1f525"}, {"🥰", "1f970"},
            {"👏", "1f44f"}, {"😁", "1f601"}, {"🤔", "1f914"}, {"🤯", "1f92f"}, {"😱", "1f631"},
            {"🤬", "1f92c"}, {"😢", "1f622"}, {"🎉", "1f389"}, {"🤩", "1f929"}, {"🤮", "1f92e"},
            {"💩", "1f4a9"}, {"🙏", "1f64f"}, {"👌", "1f44c"}, {"🕊", "1f54a_fe0f"}, {"🤡", "1f921"},
            {"🥱", "1f971"}, {"🥴", "1f974"}, {"😍", "1f60d"}, {"🐳", "1f433"}, {"❤‍🔥", "2764_fe0f_200d_1f525"},
            {"🌚", "1f31a"}, {"🌭", "1f32d"}, {"💯", "1f4af"}, {"🤣", "1f923"}, {"⚡", "26a1"},
            {"🏆", "1f3c6"}, {"💔", "1f494"}, {"🤨", "1f928"}, {"😐", "1f610"}, {"🍓", "1f353"},
            {"🍾", "1f37e"}, {"💋", "1f48b"}, {"😈", "1f608"}, {"😴", "1f634"}, {"😭", "1f62d"},
            {"🤓", "1f913"}, {"👻", "1f47b"}, {"👀", "1f440"}, {"🙈", "1f648"}, {"😇", "1f607"},
            {"😨", "1f628"}, {"🤝", "1f91d"}, {"✍", "270d_fe0f"}, {"🤗", "1f917"}, {"🫡", "1fae1"},
            {"☃", "2603_fe0f"}, {"💅", "1f485"}, {"🤪", "1f92a"}, {"🗿", "1f5ff"}, {"🆒", "1f192"},
            {"💘", "1f498"}, {"🙉", "1f649"}, {"🦄", "1f984"}, {"😘", "1f618"}, {"🙊", "1f64a"},
            {"😎", "1f60e"}, {"👾", "1f47e"}, {"😡", "1f621"},
    };

    private static final String ASSETS = "phogram_reactions/";

    /**
     * Document ids, a range of their own apart from the engine's files and photos, and apart from the still
     * pictures earlier versions served, which phones may still hold in their caches.
     */
    private static final long ID_BASE = (1L << 61) + (1L << 32);
    private static final int KIND_STATIC = 0;
    private static final int KIND_ANIMATION = 1;
    private static final int KIND_CENTER = 2;
    private static final int KINDS = 3;
    private static final long EMPTY_EFFECT_ID = ID_BASE + (long) REACTIONS.length * KINDS;

    private static final int SIDE = 160;
    private static final int DC_ID = 1;
    /** Changed whenever the list or its files change, so phones fetch the list again. */
    private static final int HASH = 0x50686f68;

    private static final Map<Long, byte[]> files = new HashMap<>();
    private static TLRPC.TL_messages_availableReactions available;

    /**
     * The spelling Telegram knows a reaction by. XMPP apps add the emoji variation selector ("❤️"), and
     * Telegram's list has the bare form ("❤"), so the same heart must not count as two reactions.
     */
    static String telegramForm(String emoji) {
        if (emoji == null) {
            return null;
        }
        String bare = emoji.replace("️", "");
        for (String[] known : REACTIONS) {
            if (known[0].equals(emoji) || known[0].equals(bare)) {
                return known[0];
            }
        }
        return emoji;
    }

    static boolean isReactionDocument(long id) {
        return id >= ID_BASE && id <= EMPTY_EFFECT_ID;
    }

    /** The reactions, built once: reading and reshaping the animations takes a moment, so it happens on first use. */
    static synchronized TLObject availableReactions(int knownHash) {
        if (available == null) {
            build();
        }
        if (knownHash == HASH) {
            return new TLRPC.TL_messages_availableReactionsNotModified();
        }
        return available;
    }

    /** A part of a reaction's file, as Telegram asks for files. */
    static synchronized TLRPC.TL_upload_file file(long id, long offset, int limit) {
        if (available == null) {
            build();
        }
        byte[] data = files.get(id);
        if (data == null) {
            return null;
        }
        int start = (int) Math.min(offset, data.length);
        int length = Math.max(0, Math.min(limit, data.length - start));
        NativeByteBuffer buffer;
        try {
            buffer = new NativeByteBuffer(length);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        buffer.buffer.put(data, start, length);
        buffer.buffer.position(0);
        TLRPC.TL_upload_file result = new TLRPC.TL_upload_file();
        result.type = id == EMPTY_EFFECT_ID || (id - ID_BASE) % KINDS != KIND_STATIC
                ? new TLRPC.TL_storage_fileUnknown() : new TLRPC.TL_storage_fileWebp();
        result.mtime = 0;
        result.bytes = buffer;
        return result;
    }

    private static void build() {
        available = new TLRPC.TL_messages_availableReactions();
        available.hash = HASH;
        byte[] emptyEffect = gzip(emptyAnimation());
        files.put(EMPTY_EFFECT_ID, emptyEffect);
        TLRPC.Document effect = document(EMPTY_EFFECT_ID, emptyEffect.length, true);
        for (int i = 0; i < REACTIONS.length; i++) {
            String emoji = REACTIONS[i][0];
            long base = ID_BASE + (long) i * KINDS;
            byte[] animation = readAsset(ASSETS + REACTIONS[i][1] + ".tgs");
            byte[] center = animation == null ? null : centered(animation);
            byte[] still = draw(emoji);
            files.put(base + KIND_STATIC, still);
            TLRPC.Document staticIcon = document(base + KIND_STATIC, still.length, false);
            TLRPC.Document animationDocument = staticIcon;
            TLRPC.Document centerDocument = staticIcon;
            if (animation != null && center != null) {
                files.put(base + KIND_ANIMATION, animation);
                files.put(base + KIND_CENTER, center);
                animationDocument = document(base + KIND_ANIMATION, animation.length, true);
                centerDocument = document(base + KIND_CENTER, center.length, true);
            }
            TLRPC.TL_availableReaction reaction = new TLRPC.TL_availableReaction();
            reaction.reaction = emoji;
            reaction.title = emoji;
            reaction.static_icon = staticIcon;
            reaction.appear_animation = animationDocument;
            reaction.select_animation = animationDocument;
            reaction.activate_animation = animationDocument;
            // Telegram's effect is a burst of particles around the emoji; Phogram has none, so it plays nothing.
            reaction.effect_animation = effect;
            reaction.around_animation = effect;
            reaction.center_icon = centerDocument;
            reaction.flags |= TLObject.FLAG_1;
            available.reactions.add(reaction);
        }
    }

    private static TLRPC.Document document(long id, int size, boolean animated) {
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = id;
        document.access_hash = id;
        document.file_reference = new byte[0];
        document.mime_type = animated ? "application/x-tgsticker" : "image/webp";
        document.size = size;
        document.dc_id = DC_ID;
        TLRPC.TL_documentAttributeImageSize imageSize = new TLRPC.TL_documentAttributeImageSize();
        imageSize.w = animated ? 512 : SIDE;
        imageSize.h = animated ? 512 : SIDE;
        document.attributes.add(imageSize);
        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = animated ? "reaction.tgs" : "reaction.webp";
        document.attributes.add(name);
        return document;
    }

    /**
     * The animation scaled to half around its middle, as Telegram's center icons are drawn: the whole animation
     * becomes one precomposition, and one layer shows it at 50%.
     */
    private static byte[] centered(byte[] tgs) {
        try {
            JSONObject root = new JSONObject(new String(gunzip(tgs), StandardCharsets.UTF_8));
            double w = root.getDouble("w");
            double h = root.getDouble("h");
            JSONArray assets = root.optJSONArray("assets");
            if (assets == null) {
                assets = new JSONArray();
            }
            JSONObject comp = new JSONObject();
            comp.put("id", "phogram_center");
            comp.put("layers", root.getJSONArray("layers"));
            assets.put(comp);

            JSONObject transform = new JSONObject();
            transform.put("o", fixed(100));
            transform.put("r", fixed(0));
            transform.put("p", fixed(new JSONArray().put(w / 2).put(h / 2).put(0)));
            transform.put("a", fixed(new JSONArray().put(w / 2).put(h / 2).put(0)));
            transform.put("s", fixed(new JSONArray().put(50).put(50).put(100)));
            JSONObject layer = new JSONObject();
            layer.put("ddd", 0);
            layer.put("ind", 1);
            layer.put("ty", 0);
            layer.put("nm", "phogram_center");
            layer.put("refId", "phogram_center");
            layer.put("sr", 1);
            layer.put("ks", transform);
            layer.put("ao", 0);
            layer.put("w", w);
            layer.put("h", h);
            layer.put("ip", root.optDouble("ip", 0));
            layer.put("op", root.getDouble("op"));
            layer.put("st", 0);
            layer.put("bm", 0);

            root.put("assets", assets);
            root.put("layers", new JSONArray().put(layer));
            return gzip(root.toString());
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static JSONObject fixed(Object value) throws Exception {
        JSONObject property = new JSONObject();
        property.put("a", 0);
        property.put("k", value);
        return property;
    }

    /** One second of nothing, in place of Telegram's particle effects. */
    private static String emptyAnimation() {
        return "{\"v\":\"5.8.1\",\"fr\":60,\"ip\":0,\"op\":60,\"w\":512,\"h\":512,\"nm\":\"empty\",\"ddd\":0,"
                + "\"assets\":[],\"layers\":[]}";
    }

    private static byte[] readAsset(String path) {
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static byte[] gzip(String json) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
                zip.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (Exception e) {
            FileLog.e(e);
            return new byte[0];
        }
    }

    /**
     * A still picture for the few places Telegram shows a reaction without animating it: the emoji pack's
     * picture on a transparent square, or the system's emoji font when the pack lacks it.
     */
    private static byte[] draw(String emoji) {
        Bitmap bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Bitmap packed = Emoji.getEmojiBitmap(emoji);
        if (packed != null) {
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(packed, null, new Rect(0, 0, SIDE, SIDE), paint);
        } else {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(SIDE * 0.8f);
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            canvas.drawText(emoji, SIDE / 2f, SIDE / 2f - (metrics.ascent + metrics.descent) / 2f, paint);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= 30
                ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP;
        bitmap.compress(format, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }
}
