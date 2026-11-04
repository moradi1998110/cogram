package org.telegram.cogram;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;
import java.util.Map;

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
        super(context, DATABASE, null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE peers (id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT UNIQUE NOT NULL)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, uuid TEXT UNIQUE NOT NULL, time INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
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

    /** Returns the time a message was sent, or -1 for an unknown id. */
    synchronized long messageTime(int id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT time FROM messages WHERE id = ?", new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }
}
