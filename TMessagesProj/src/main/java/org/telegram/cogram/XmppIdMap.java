package org.telegram.cogram;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the numeric ids Telegram needs for XMPP addresses and message uuids. Message ids grow with
 * the time sent, because Telegram orders a chat by id and XMPP archives can deliver old messages late.
 */
final class XmppIdMap extends SQLiteOpenHelper {

    private static final String DATABASE = "cogram_ids.db";
    private static final long PEER_ID_OFFSET = 10000;
    private static final long EPOCH_SECONDS = 1704067200;
    private static final int IDS_PER_SECOND = 4;

    private final Map<String, Long> peerIds = new HashMap<>();
    private final Map<String, Integer> messageIds = new HashMap<>();

    XmppIdMap(Context context) {
        super(context, DATABASE, null, 7);
    }

    /** Forgets every id, so the next account starts with fresh message ids and pts. */
    void erase(Context context) {
        close();
        peerIds.clear();
        messageIds.clear();
        context.deleteDatabase(DATABASE);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE peers (id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT UNIQUE NOT NULL)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, uuid TEXT UNIQUE NOT NULL, time INTEGER NOT NULL, pts INTEGER NOT NULL DEFAULT 0, channel INTEGER NOT NULL DEFAULT 0)");
        createReads(db);
        createSendTables(db);
        createRoomReads(db);
        createCaptions(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN pts INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) {
            createReads(db);
        }
        if (oldVersion < 4) {
            createSendTables(db);
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE messages ADD COLUMN channel INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 6) {
            createRoomReads(db);
        }
        if (oldVersion < 7) {
            createCaptions(db);
        }
    }

    /**
     * The captions of photos and files: the caption's own message (its uuid), the id of the file message it is
     * attached to (our uuid for ours, the sender's stanza id for theirs), who wrote it, and its text, so the file
     * shows its caption even before the caption's own message is out.
     */
    private static void createCaptions(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE captions (uuid TEXT PRIMARY KEY, target TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX captions_target ON captions (target)");
    }

    synchronized void putCaption(String uuid, String target, String sender, String text) {
        ContentValues values = new ContentValues();
        values.put("uuid", uuid);
        values.put("target", target);
        values.put("sender", sender);
        values.put("text", text);
        getWritableDatabase().insertWithOnConflict("captions", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Forgets a caption that did not go out, so its file shows none. */
    synchronized void removeCaption(String uuid) {
        getWritableDatabase().delete("captions", "uuid = ?", new String[]{uuid});
    }

    /** The caption a sender attached to this message, by any of its ids; null when none. */
    synchronized String captionFor(String sender, String... targets) {
        for (String target : targets) {
            if (target == null) continue;
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT text FROM captions WHERE target = ? AND sender = ? ORDER BY rowid DESC LIMIT 1",
                    new String[]{target, sender})) {
                if (cursor.moveToFirst()) return cursor.getString(0);
            }
        }
        return null;
    }

    synchronized java.util.Set<String> captionUuids() {
        java.util.HashSet<String> uuids = new java.util.HashSet<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT uuid FROM captions", null)) {
            while (cursor.moveToNext()) uuids.add(cursor.getString(0));
        }
        return uuids;
    }

    /** {target, sender} of a caption message, or null when the message is no caption. */
    synchronized String[] captionOf(String uuid) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT target, sender FROM captions WHERE uuid = ?", new String[]{uuid})) {
            return cursor.moveToFirst() ? new String[]{cursor.getString(0), cursor.getString(1)} : null;
        }
    }

    /**
     * sent_media keeps a sent file as Telegram described it, with the waveform and name the engine
     * does not know. sends lets a message Telegram resends after a restart find the original.
     */
    private static void createSendTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sent_media (id INTEGER PRIMARY KEY, media BLOB NOT NULL)");
        db.execSQL("CREATE TABLE sends (random_id INTEGER PRIMARY KEY, uuid TEXT NOT NULL)");
    }

    synchronized void putSentMedia(int id, byte[] media) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("media", media);
        getWritableDatabase().insertWithOnConflict("sent_media", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized byte[] sentMedia(int id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT media FROM sent_media WHERE id = ?", new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? cursor.getBlob(0) : null;
        }
    }

    synchronized void rememberSend(long randomId, String uuid) {
        ContentValues values = new ContentValues();
        values.put("random_id", randomId);
        values.put("uuid", uuid);
        getWritableDatabase().insertWithOnConflict("sends", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized String sendUuid(long randomId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT uuid FROM sends WHERE random_id = ?", new String[]{String.valueOf(randomId)})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    synchronized void forgetSend(long randomId) {
        getWritableDatabase().delete("sends", "random_id = ?", new String[]{String.valueOf(randomId)});
    }

    /** When a contact had read up to which message, which Telegram shows as a message's read time. */
    private static void createReads(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE reads (peer INTEGER NOT NULL, max_id INTEGER NOT NULL, date INTEGER NOT NULL)");
    }

    synchronized void addRead(long peer, int maxId, int date) {
        ContentValues values = new ContentValues();
        values.put("peer", peer);
        values.put("max_id", maxId);
        values.put("date", date);
        getWritableDatabase().insert("reads", null, values);
    }

    /** Returns when the contact first had read this message, or 0 when that is not known. */
    synchronized int readDate(long peer, int messageId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT MIN(date) FROM reads WHERE peer = ? AND max_id >= ?",
                new String[]{String.valueOf(peer), String.valueOf(messageId)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /**
     * When each room member had read up to which message: a member who read a message has read every one
     * before it, as Telegram counts a group read. Rows older than Telegram shows readers for are dropped.
     */
    private static void createRoomReads(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE room_reads (room INTEGER NOT NULL, reader INTEGER NOT NULL, max_id INTEGER NOT NULL, date INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX room_reads_room ON room_reads (room, max_id)");
    }

    private static final int ROOM_READS_KEPT_SECONDS = 8 * 86400;

    synchronized void addRoomRead(long room, long reader, int maxId, int date) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("room", room);
        values.put("reader", reader);
        values.put("max_id", maxId);
        values.put("date", date);
        db.insert("room_reads", null, values);
        db.delete("room_reads", "date < ?", new String[]{String.valueOf(date - ROOM_READS_KEPT_SECONDS)});
    }

    /** The newest message anyone else has read in the room, or 0. */
    synchronized int roomReadMax(long room) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT MAX(max_id) FROM room_reads WHERE room = ?",
                new String[]{String.valueOf(room)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Who has read this room message, each with when they first had read it. */
    synchronized Map<Long, Integer> roomReaders(long room, int messageId) {
        Map<Long, Integer> readers = new HashMap<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT reader, MIN(date) FROM room_reads WHERE room = ? AND max_id >= ? GROUP BY reader",
                new String[]{String.valueOf(room), String.valueOf(messageId)})) {
            while (cursor.moveToNext()) {
                readers.put(cursor.getLong(0), cursor.getInt(1));
            }
        }
        return readers;
    }

    /** An edit gives the engine's message a new uuid, and Telegram must keep seeing the same message. */
    synchronized void replaceUuid(String previous, String current) {
        Integer id = messageIds.remove(previous);
        ContentValues values = new ContentValues();
        values.put("uuid", current);
        getWritableDatabase().update("messages", values, "uuid = ?", new String[]{previous});
        if (id != null) {
            messageIds.put(current, id);
        }
    }

    /**
     * Runs work that may number many messages at once in one transaction. Each new id is a write,
     * and a chat opened for the first time would otherwise wait for hundreds of separate commits.
     */
    synchronized <T> T inTransaction(java.util.function.Supplier<T> work) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            T result = work.get();
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    synchronized long peerId(String jid) {
        Long known = peerIds.get(jid);
        if (known != null) {
            return known;
        }
        SQLiteDatabase db = getWritableDatabase();
        long row = -1;
        try (Cursor cursor = db.rawQuery("SELECT id FROM peers WHERE jid = ?", new String[]{jid})) {
            if (cursor.moveToFirst()) {
                row = cursor.getLong(0);
            }
        }
        if (row == -1) {
            ContentValues values = new ContentValues();
            values.put("jid", jid);
            row = db.insertOrThrow("peers", null, values);
        }
        long id = row + PEER_ID_OFFSET;
        peerIds.put(jid, id);
        return id;
    }

    synchronized String peerJid(long id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT jid FROM peers WHERE id = ?", new String[]{String.valueOf(id - PEER_ID_OFFSET)})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    /** Returns the id of a message, or 0 when Telegram has not seen it yet. */
    synchronized int findMessage(String uuid) {
        Integer known = messageIds.get(uuid);
        if (known != null) {
            return known;
        }
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id FROM messages WHERE uuid = ?", new String[]{uuid})) {
            if (cursor.moveToFirst()) {
                int id = cursor.getInt(0);
                messageIds.put(uuid, id);
                return id;
            }
        }
        return 0;
    }

    synchronized int messageId(String uuid, long timeMillis) {
        int id = findMessage(uuid);
        if (id != 0) {
            return id;
        }
        SQLiteDatabase db = getWritableDatabase();
        id = (int) Math.max(1, (timeMillis / 1000 - EPOCH_SECONDS) * IDS_PER_SECOND);
        while (isMessageIdTaken(db, id)) {
            id++;
        }
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("uuid", uuid);
        values.put("time", timeMillis);
        db.insertOrThrow("messages", null, values);
        messageIds.put(uuid, id);
        return id;
    }

    private static boolean isMessageIdTaken(SQLiteDatabase db, int id) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM messages WHERE id = ?", new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst();
        }
    }

    synchronized void setMessagePts(int id, int pts, long channel) {
        ContentValues values = new ContentValues();
        values.put("pts", pts);
        values.put("channel", channel);
        getWritableDatabase().update("messages", values, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Returns the room a message was sent to Telegram in, or 0 for a one-to-one chat. */
    synchronized long channelOf(int id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT channel FROM messages WHERE id = ?", new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    /** Returns a room's messages sent to Telegram after the room's own pts. */
    synchronized Set<String> channelMessagesAfterPts(long channel, int pts) {
        HashSet<String> uuids = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT uuid FROM messages WHERE channel = ? AND pts > ?", new String[]{String.valueOf(channel), String.valueOf(pts)})) {
            while (cursor.moveToNext()) {
                uuids.add(cursor.getString(0));
            }
        }
        return uuids;
    }

    synchronized void setMessagePts(int id, int pts) {
        ContentValues values = new ContentValues();
        values.put("pts", pts);
        getWritableDatabase().update("messages", values, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Returns the messages sent to Telegram as updates after the given pts. */
    synchronized Set<String> messagesAfterPts(int pts) {
        HashSet<String> uuids = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT uuid FROM messages WHERE channel = 0 AND pts > ?", new String[]{String.valueOf(pts)})) {
            while (cursor.moveToNext()) {
                uuids.add(cursor.getString(0));
            }
        }
        return uuids;
    }

    /** Returns the engine's id of a message, or null for an id Telegram was never given. */
    synchronized String messageUuid(int id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT uuid FROM messages WHERE id = ?", new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    /** Returns the time a message was sent, or -1 for an unknown id. */
    synchronized long messageTime(int id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT time FROM messages WHERE id = ?", new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }
}
