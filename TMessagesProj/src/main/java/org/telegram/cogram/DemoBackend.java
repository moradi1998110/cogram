package org.telegram.cogram;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory chats used until the XMPP backend exists. Sent messages are answered with an echo.
 * Only the id and pts counters survive a restart, because Telegram's local cache depends on them.
 */
final class DemoBackend implements GatewayBackend {

    private static final long SELF_ID = 1000;
    private static final long ALICE_ID = 1001;
    private static final long BOB_ID = 1002;
    private static final int FIRST_LOCAL_ID = 100;
    private static final int ECHO_DELAY_MS = 1000;

    private final SharedPreferences preferences;
    private final Map<Long, TLRPC.User> contacts = new LinkedHashMap<>();
    private final Map<Long, ArrayList<TLRPC.Message>> chats = new LinkedHashMap<>();
    private final Map<Long, Integer> readInboxMax = new LinkedHashMap<>();
    private final TLRPC.User self;
    private UpdatesListener listener;
    private int nextId;
    private int pts;

    DemoBackend(int account) {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("cogram_demo" + account, Activity.MODE_PRIVATE);
        nextId = preferences.getInt("nextId", FIRST_LOCAL_ID);
        pts = preferences.getInt("pts", 1);

        self = createUser(SELF_ID, "Cogram", "Demo", true);
        contacts.put(ALICE_ID, createUser(ALICE_ID, "Alice", "Demo", false));
        contacts.put(BOB_ID, createUser(BOB_ID, "Bob", "Demo", false));

        int now = (int) (System.currentTimeMillis() / 1000);
        addSeedMessage(1, ALICE_ID, false, now - 7200, "Hello from the Cogram gateway");
        addSeedMessage(2, ALICE_ID, true, now - 7100, "This chat has no Telegram server behind it");
        addSeedMessage(3, ALICE_ID, false, now - 7000, "Send a message and I will echo it");
        addSeedMessage(4, BOB_ID, false, now - 3600, "Second demo conversation");
    }

    private static TLRPC.User createUser(long id, String firstName, String lastName, boolean isSelf) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.access_hash = id;
        user.first_name = firstName;
        user.last_name = lastName;
        user.status = new TLRPC.TL_userStatusRecently();
        user.self = isSelf;
        user.contact = !isSelf;
        user.mutual_contact = !isSelf;
        user.flags = TLObject.FLAG_0 | TLObject.FLAG_1 | TLObject.FLAG_2 | TLObject.FLAG_6;
        return user;
    }

    private void addSeedMessage(int id, long userId, boolean out, int date, String text) {
        chat(userId).add(createMessage(id, userId, out, date, text));
    }

    private ArrayList<TLRPC.Message> chat(long userId) {
        ArrayList<TLRPC.Message> messages = chats.get(userId);
        if (messages == null) {
            messages = new ArrayList<>();
            chats.put(userId, messages);
        }
        return messages;
    }

    private static TLRPC.Peer peerUser(long userId) {
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = userId;
        return peer;
    }

    private static TLRPC.Message createMessage(int id, long userId, boolean out, int date, String text) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.out = out;
        message.date = date;
        message.message = text;
        message.peer_id = peerUser(userId);
        message.from_id = peerUser(out ? SELF_ID : userId);
        message.dialog_id = userId;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.flags = TLObject.FLAG_8 | TLObject.FLAG_9;
        return message;
    }

    private void saveCounters() {
        preferences.edit().putInt("nextId", nextId).putInt("pts", pts).apply();
    }

    @Override
    public synchronized void setUpdatesListener(UpdatesListener listener) {
        this.listener = listener;
    }

    @Override
    public TLRPC.User selfUser() {
        return self;
    }

    @Override
    public synchronized List<TLRPC.User> users() {
        ArrayList<TLRPC.User> users = new ArrayList<>(contacts.values());
        users.add(self);
        return users;
    }

    @Override
    public synchronized List<TLRPC.Dialog> dialogs() {
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
        for (Map.Entry<Long, ArrayList<TLRPC.Message>> entry : chats.entrySet()) {
            ArrayList<TLRPC.Message> messages = entry.getValue();
            TLRPC.Message top = messages.get(messages.size() - 1);
            Integer readMax = readInboxMax.get(entry.getKey());
            int read = readMax == null ? 0 : readMax;
            int unread = 0;
            int readOutbox = 0;
            for (TLRPC.Message message : messages) {
                if (message.out) {
                    readOutbox = Math.max(readOutbox, message.id);
                } else if (message.id > read) {
                    unread++;
                }
            }
            TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
            dialog.id = entry.getKey();
            dialog.peer = peerUser(entry.getKey());
            dialog.top_message = top.id;
            dialog.read_inbox_max_id = read;
            dialog.read_outbox_max_id = readOutbox;
            dialog.unread_count = unread;
            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
            dialogs.add(dialog);
        }
        return dialogs;
    }

    @Override
    public synchronized List<TLRPC.Message> topMessages() {
        ArrayList<TLRPC.Message> tops = new ArrayList<>();
        for (ArrayList<TLRPC.Message> messages : chats.values()) {
            tops.add(messages.get(messages.size() - 1));
        }
        return tops;
    }

    @Override
    public synchronized List<TLRPC.Message> history(long userId, int offsetId, int limit, int maxId, int minId) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        ArrayList<TLRPC.Message> messages = chats.get(userId);
        if (messages == null) {
            return result;
        }
        for (int a = messages.size() - 1; a >= 0 && result.size() < limit; a--) {
            TLRPC.Message message = messages.get(a);
            if (offsetId != 0 && message.id >= offsetId) {
                continue;
            }
            if (maxId != 0 && message.id > maxId) {
                continue;
            }
            if (message.id <= minId) {
                continue;
            }
            result.add(message);
        }
        return result;
    }

    @Override
    public synchronized TLRPC.TL_updateShortSentMessage sendText(long userId, String text) {
        int date = (int) (System.currentTimeMillis() / 1000);
        int id = nextId++;
        pts++;
        saveCounters();
        chat(userId).add(createMessage(id, userId, true, date, text));

        TLRPC.TL_updateShortSentMessage sent = new TLRPC.TL_updateShortSentMessage();
        sent.flags = TLObject.FLAG_1;
        sent.out = true;
        sent.id = id;
        sent.pts = pts;
        sent.pts_count = 1;
        sent.date = date;

        Utilities.stageQueue.postRunnable(() -> deliverEcho(userId, text), ECHO_DELAY_MS);
        return sent;
    }

    private void deliverEcho(long userId, String text) {
        UpdatesListener target;
        TLRPC.TL_updateShortMessage update = new TLRPC.TL_updateShortMessage();
        synchronized (this) {
            int date = (int) (System.currentTimeMillis() / 1000);
            String echo = "Echo: " + text;
            int id = nextId++;
            pts++;
            saveCounters();
            chat(userId).add(createMessage(id, userId, false, date, echo));
            update.id = id;
            update.user_id = userId;
            update.message = echo;
            update.pts = pts;
            update.pts_count = 1;
            update.date = date;
            target = listener;
        }
        if (target != null) {
            target.onUpdates(update);
        }
    }

    @Override
    public synchronized TLRPC.TL_messages_affectedMessages markRead(long userId, int maxId) {
        readInboxMax.put(userId, maxId);
        pts++;
        saveCounters();
        TLRPC.TL_messages_affectedMessages affected = new TLRPC.TL_messages_affectedMessages();
        affected.pts = pts;
        affected.pts_count = 1;
        return affected;
    }

    @Override
    public synchronized int pts() {
        return pts;
    }
}
