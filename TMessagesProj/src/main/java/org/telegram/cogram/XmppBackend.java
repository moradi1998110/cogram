package org.telegram.cogram;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Serves one-to-one chats from the Conversations engine. Messages are sent unencrypted for now, and
 * group chats are left out; both come in later phases.
 */
final class XmppBackend implements GatewayBackend {

    private static final int HISTORY_SCAN_FACTOR = 2;
    private static final int HISTORY_AROUND_LIMIT = 2000;
    private static final String TEXT_FILE = "📎";
    private static final String TEXT_UNREADABLE = "🔒";

    private final Context context = ApplicationLoader.applicationContext;
    private final SharedPreferences preferences = context.getSharedPreferences("cogram_xmpp", Activity.MODE_PRIVATE);
    private final XmppIdMap ids = new XmppIdMap(context);
    private final ExecutorService events = Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncPending = new AtomicBoolean();
    private final ArrayList<Runnable> waiting = new ArrayList<>();
    private final Map<Long, Integer> readOutboxMax = new HashMap<>();
    private final TLRPC.User self;
    private UpdatesListener listener;
    private XmppConnectionService service;
    private int pts;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            XmppConnectionService connected = ((XmppConnectionService.XmppConnectionBinder) binder).getService();
            events.execute(() -> onServiceReady(connected));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    XmppBackend() {
        pts = preferences.getInt("pts", 1);
        Jid address = firstAccountAddress(context);
        self = createUser(ids.peerId(address.asBareJid().toString()), address.getLocal(), true);
        Intent intent = new Intent(context, XmppConnectionService.class);
        try {
            context.startService(intent);
        } catch (Exception e) {
            // Starting is refused in the background, and binding below still creates the service.
            FileLog.e(e);
        }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    static boolean hasAccount(Context context) {
        return firstAccountAddress(context) != null;
    }

    private static Jid firstAccountAddress(Context context) {
        Collection<DatabaseBackend.AccountWithOptions> accounts = Conversations.getInstance(context).getAccounts();
        for (Jid address : DatabaseBackend.AccountWithOptions.getAddresses(accounts)) {
            return address;
        }
        return null;
    }

    private void onServiceReady(XmppConnectionService connected) {
        try {
            connected.restoredFromDatabaseLatch.await();
        } catch (InterruptedException e) {
            FileLog.e(e);
            return;
        }
        ArrayList<Runnable> ready;
        synchronized (this) {
            service = connected;
            ready = new ArrayList<>(waiting);
            waiting.clear();
        }
        connected.setOnConversationListChangedListener(this::scheduleSync);
        for (Runnable runnable : ready) {
            runnable.run();
        }
        scheduleSync();
    }

    private void scheduleSync() {
        if (syncPending.compareAndSet(false, true)) {
            events.execute(() -> {
                syncPending.set(false);
                sync();
            });
        }
    }

    /** Passes on what the engine has that Telegram has not seen. */
    private void sync() {
        ArrayList<TLRPC.Updates> updates = new ArrayList<>();
        UpdatesListener target;
        synchronized (this) {
            target = listener;
            for (Conversation conversation : singleChats()) {
                long peerId = peerId(conversation);
                int readMax = 0;
                for (Message message : loadedMessages(conversation)) {
                    if (ids.findMessage(message.getUuid()) == 0) {
                        updates.add(createNewMessageUpdate(message, peerId));
                    }
                    if (message.getStatus() == Message.STATUS_SEND_DISPLAYED) {
                        readMax = Math.max(readMax, ids.messageId(message.getUuid(), message.getTimeSent()));
                    }
                }
                Integer known = readOutboxMax.get(peerId);
                if (readMax > (known == null ? 0 : known)) {
                    readOutboxMax.put(peerId, readMax);
                    updates.add(createReadOutboxUpdate(peerId, readMax));
                }
            }
        }
        if (target != null) {
            for (TLRPC.Updates update : updates) {
                target.onUpdates(update);
            }
        }
    }

    private TLRPC.Updates createNewMessageUpdate(Message message, long peerId) {
        TLRPC.Message tl = toTl(message, peerId);
        pts++;
        savePts();
        TLRPC.TL_updateShortMessage update = new TLRPC.TL_updateShortMessage();
        update.id = tl.id;
        update.user_id = peerId;
        update.message = tl.message;
        update.out = tl.out;
        update.pts = pts;
        update.pts_count = 1;
        update.date = tl.date;
        return update;
    }

    private static TLRPC.Updates createReadOutboxUpdate(long peerId, int maxId) {
        TL_update.TL_updateReadHistoryOutbox read = new TL_update.TL_updateReadHistoryOutbox();
        read.peer = peerUser(peerId);
        read.max_id = maxId;
        TLRPC.TL_updateShort update = new TLRPC.TL_updateShort();
        update.update = read;
        return update;
    }

    private void savePts() {
        preferences.edit().putInt("pts", pts).apply();
    }

    private Account account() {
        List<Account> accounts = service.getAccounts();
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private List<Conversation> singleChats() {
        ArrayList<Conversation> all = new ArrayList<>();
        service.populateWithOrderedConversations(all);
        ArrayList<Conversation> single = new ArrayList<>();
        for (Conversation conversation : all) {
            if (conversation.getMode() == Conversation.MODE_SINGLE) {
                single.add(conversation);
            }
        }
        return single;
    }

    private long peerId(Conversation conversation) {
        return ids.peerId(conversation.getAddress().asBareJid().toString());
    }

    private Conversation conversation(long peerId) {
        String jid = ids.peerJid(peerId);
        if (jid == null || account() == null) {
            return null;
        }
        return service.findOrCreateConversation(account(), Jid.of(jid), false, false);
    }

    private List<Message> loadedMessages(Conversation conversation) {
        ArrayList<Message> loaded = new ArrayList<>();
        conversation.populateWithMessages(loaded);
        ArrayList<Message> shown = new ArrayList<>();
        for (Message message : loaded) {
            if (isShown(message)) {
                shown.add(message);
            }
        }
        return shown;
    }

    private static boolean isShown(Message message) {
        int type = message.getType();
        return type != Message.TYPE_STATUS && type != Message.TYPE_RTP_SESSION;
    }

    private static String textOf(Message message) {
        switch (message.getEncryption()) {
            case Message.ENCRYPTION_PGP:
            case Message.ENCRYPTION_DECRYPTION_FAILED:
            case Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE:
            case Message.ENCRYPTION_AXOLOTL_FAILED:
                return TEXT_UNREADABLE;
            default:
                return message.getType() == Message.TYPE_TEXT ? message.getBody() : TEXT_FILE;
        }
    }

    private TLRPC.Message toTl(Message message, long peerId) {
        boolean out = message.getStatus() != Message.STATUS_RECEIVED;
        TLRPC.TL_message tl = new TLRPC.TL_message();
        tl.id = ids.messageId(message.getUuid(), message.getTimeSent());
        tl.out = out;
        tl.date = (int) (message.getTimeSent() / 1000);
        tl.message = textOf(message);
        tl.peer_id = peerUser(peerId);
        tl.from_id = peerUser(out ? self.id : peerId);
        tl.dialog_id = peerId;
        tl.media = new TLRPC.TL_messageMediaEmpty();
        tl.flags = TLObject.FLAG_8 | TLObject.FLAG_9;
        return tl;
    }

    private static TLRPC.Peer peerUser(long userId) {
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = userId;
        return peer;
    }

    private static TLRPC.User createUser(long id, String name, boolean isSelf) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.access_hash = id;
        user.first_name = name;
        user.last_name = "";
        user.status = new TLRPC.TL_userStatusRecently();
        user.self = isSelf;
        user.contact = !isSelf;
        user.mutual_contact = !isSelf;
        user.flags = TLObject.FLAG_0 | TLObject.FLAG_1 | TLObject.FLAG_6;
        return user;
    }

    @Override
    public synchronized void whenReady(Runnable runnable) {
        if (service == null) {
            waiting.add(runnable);
        } else {
            runnable.run();
        }
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
        ArrayList<TLRPC.User> users = new ArrayList<>();
        users.add(self);
        if (service != null) {
            for (Conversation conversation : singleChats()) {
                users.add(createUser(peerId(conversation), conversation.getName().toString(), false));
            }
        }
        return users;
    }

    @Override
    public synchronized List<TLRPC.Dialog> dialogs() {
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
        for (Conversation conversation : singleChats()) {
            List<Message> messages = loadedMessages(conversation);
            if (messages.isEmpty()) {
                continue;
            }
            long peerId = peerId(conversation);
            int top = ids.messageId(messages.get(messages.size() - 1).getUuid(), messages.get(messages.size() - 1).getTimeSent());
            int readOutbox = 0;
            for (Message message : messages) {
                if (message.getStatus() == Message.STATUS_SEND_DISPLAYED) {
                    readOutbox = Math.max(readOutbox, ids.messageId(message.getUuid(), message.getTimeSent()));
                }
            }
            Message firstUnread = conversation.getFirstUnreadMessage();
            TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
            dialog.id = peerId;
            dialog.peer = peerUser(peerId);
            dialog.top_message = top;
            dialog.read_inbox_max_id = firstUnread == null ? top : ids.messageId(firstUnread.getUuid(), firstUnread.getTimeSent()) - 1;
            dialog.read_outbox_max_id = readOutbox;
            dialog.unread_count = conversation.unreadCount();
            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
            dialogs.add(dialog);
        }
        return dialogs;
    }

    @Override
    public synchronized List<TLRPC.Message> topMessages() {
        ArrayList<TLRPC.Message> tops = new ArrayList<>();
        for (Conversation conversation : singleChats()) {
            List<Message> messages = loadedMessages(conversation);
            if (!messages.isEmpty()) {
                tops.add(toTl(messages.get(messages.size() - 1), peerId(conversation)));
            }
        }
        return tops;
    }

    @Override
    public synchronized List<TLRPC.Message> history(long userId, int offsetId, int addOffset, int limit, int maxId, int minId) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        Conversation conversation = conversation(userId);
        if (conversation == null) {
            return result;
        }
        boolean aroundOffset = addOffset < 0;
        long before = offsetId == 0 || aroundOffset ? -1 : ids.messageTime(offsetId) + 1;
        int scan = aroundOffset ? HISTORY_AROUND_LIMIT : limit * HISTORY_SCAN_FACTOR;
        ArrayList<TLRPC.Message> newestFirst = new ArrayList<>();
        for (Message message : service.databaseBackend.getMessages(conversation, scan, before)) {
            if (isShown(message)) {
                newestFirst.add(toTl(message, userId));
            }
        }
        int start = 0;
        if (offsetId != 0) {
            while (start < newestFirst.size() && newestFirst.get(start).id >= offsetId) {
                start++;
            }
            start = Math.max(0, start + addOffset);
        }
        for (int a = start; a < newestFirst.size() && result.size() < limit; a++) {
            TLRPC.Message message = newestFirst.get(a);
            if ((maxId == 0 || message.id <= maxId) && message.id > minId) {
                result.add(message);
            }
        }
        return result;
    }

    @Override
    public synchronized TLRPC.TL_updateShortSentMessage sendText(long userId, String text) {
        Conversation conversation = conversation(userId);
        Message message = new Message(conversation, text, Message.ENCRYPTION_NONE);
        int id = ids.messageId(message.getUuid(), message.getTimeSent());
        pts++;
        savePts();
        service.sendMessage(message);

        TLRPC.TL_updateShortSentMessage sent = new TLRPC.TL_updateShortSentMessage();
        sent.flags = TLObject.FLAG_1;
        sent.out = true;
        sent.id = id;
        sent.pts = pts;
        sent.pts_count = 1;
        sent.date = (int) (message.getTimeSent() / 1000);
        return sent;
    }

    @Override
    public synchronized TLRPC.TL_messages_affectedMessages markRead(long userId, int maxId) {
        Conversation conversation = conversation(userId);
        if (conversation != null) {
            service.sendReadMarker(conversation, null);
        }
        pts++;
        savePts();
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
