package org.telegram.cogram;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;

import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;

/**
 * The reactions Telegram's reaction bar offers. Telegram's server sends each one with animations from its
 * own servers; Phogram reaches no such server, so each reaction is the emoji's picture from the emoji pack
 * already in the app, served to Telegram as a small sticker file. XMPP reactions (XEP-0444) are plain emoji,
 * so any of these reaches every XMPP app.
 */
final class ReactionCatalog {

    /** Telegram's usual reactions, in its order, without the ones only its server can give meaning. */
    private static final String[] EMOJI = {
            "👍", "👎", "❤", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱", "🤬", "😢", "🎉", "🤩", "🤮",
            "💩", "🙏", "👌", "🕊", "🤡", "🥱", "🥴", "😍", "🐳", "❤‍🔥", "🌚", "🌭", "💯", "🤣", "⚡",
            "🍌", "🏆", "💔", "🤨", "😐", "🍓", "🍾", "💋", "😈", "😴", "😭", "🤓", "👻", "👨‍💻", "👀",
            "🎃", "🙈", "😇", "😨", "🤝", "✍", "🤗", "🫡", "🎅", "🎄", "☃", "💅", "🤪", "🗿", "🆒",
            "💘", "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾", "🤷", "😡",
    };

    /** Document ids of the pictures: a range of their own, apart from the engine's files and photos. */
    private static final long ID_BASE = 1L << 61;
    private static final int SIDE = 160;
    private static final int DC_ID = 1;
    private static final int HASH = 0x50686f67;

    private static byte[][] pictures;
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
        for (String known : EMOJI) {
            if (known.equals(emoji) || known.equals(bare)) {
                return known;
            }
        }
        return emoji;
    }

    static boolean isReactionDocument(long id) {
        return id >= ID_BASE && id < ID_BASE + EMOJI.length;
    }

    /** The reactions, built once: drawing seventy pictures takes a moment, so it happens on first use. */
    static synchronized TLObject availableReactions(int knownHash) {
        if (available == null) {
            build();
        }
        if (knownHash == HASH) {
            return new TLRPC.TL_messages_availableReactionsNotModified();
        }
        return available;
    }

    /** A part of a reaction's picture, as Telegram asks for files. */
    static synchronized TLRPC.TL_upload_file file(long id, long offset, int limit) {
        if (pictures == null) {
            build();
        }
        byte[] picture = pictures[(int) (id - ID_BASE)];
        int start = (int) Math.min(offset, picture.length);
        int length = Math.max(0, Math.min(limit, picture.length - start));
        NativeByteBuffer buffer;
        try {
            buffer = new NativeByteBuffer(length);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        buffer.buffer.put(picture, start, length);
        buffer.buffer.position(0);
        TLRPC.TL_upload_file result = new TLRPC.TL_upload_file();
        result.type = new TLRPC.TL_storage_fileWebp();
        result.mtime = 0;
        result.bytes = buffer;
        return result;
    }

    private static void build() {
        pictures = new byte[EMOJI.length][];
        available = new TLRPC.TL_messages_availableReactions();
        available.hash = HASH;
        for (int i = 0; i < EMOJI.length; i++) {
            pictures[i] = draw(EMOJI[i]);
            TLRPC.Document picture = document(ID_BASE + i, pictures[i].length);
            TLRPC.TL_availableReaction reaction = new TLRPC.TL_availableReaction();
            reaction.reaction = EMOJI[i];
            reaction.title = EMOJI[i];
            // Every place Telegram shows a reaction gets the same still picture; there are no animations.
            reaction.static_icon = picture;
            reaction.appear_animation = picture;
            reaction.select_animation = picture;
            reaction.activate_animation = picture;
            reaction.effect_animation = picture;
            reaction.around_animation = picture;
            reaction.center_icon = picture;
            reaction.flags |= TLObject.FLAG_1;
            available.reactions.add(reaction);
        }
    }

    private static TLRPC.Document document(long id, int size) {
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = id;
        document.access_hash = id;
        document.file_reference = new byte[0];
        document.mime_type = "image/webp";
        document.size = size;
        document.dc_id = DC_ID;
        TLRPC.TL_documentAttributeImageSize imageSize = new TLRPC.TL_documentAttributeImageSize();
        imageSize.w = SIDE;
        imageSize.h = SIDE;
        document.attributes.add(imageSize);
        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = "reaction.webp";
        document.attributes.add(name);
        return document;
    }

    /** The emoji pack's picture on a transparent square, or the system's emoji font when the pack lacks it. */
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
