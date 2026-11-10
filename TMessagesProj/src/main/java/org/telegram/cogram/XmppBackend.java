package org.telegram.cogram;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.IBinder;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.interfaces.OnAvatarPublication;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.muc.Affiliation;
import eu.siacs.conversations.xmpp.jingle.RtpCapability;
import eu.siacs.conversations.xmpp.manager.ModerationManager;
import eu.siacs.conversations.xmpp.manager.MultiUserChatManager;
import eu.siacs.conversations.xmpp.manager.PresenceManager;
import eu.siacs.conversations.xmpp.manager.ReactionManager;

/**
 * Serves one-to-one chats from the Conversations engine. Messages are sent unencrypted for now, and
 * group chats are left out; both come in later phases.
 */
final class XmppBackend implements GatewayBackend {

    private static final int HISTORY_SCAN_FACTOR = 2;
    private static final int HISTORY_AROUND_LIMIT = 2000;
    private static final String TEXT_FILE = "📎";
    private static final String TEXT_UNREADABLE = "🔒";
    private static final int PHOTO_DC_ID = 1;
    private static final int PHOTO_SMALL_SIDE = 160;
    // Presence carries no expiry, so an online contact stays online until their presence goes away.
    private static final int ONLINE_EXPIRES_SECONDS = 86400;
    private static final int AVATAR_PUBLISH_TIMEOUT_SECONDS = 60;
    private static final int AVATAR_ECHO_WAIT_MS = 10000;
    private static final int AVATAR_ECHO_POLL_MS = 200;
    private static final int CALL_HISTORY_SCAN = 500;
    private static final String CONTACT_REQUEST_PREFIX = "contact-request:";
    private static final String PREFERENCE_LAST_SEEN = "seen_";
    private static final String PREFERENCE_CHANNEL_PTS = "channel_pts_";
    private static final String ROOM_CREATED_PREFIX = "room-created:";
    private static final String ERROR_NO_OMEMO = "USER_PRIVACY_RESTRICTED";
    private static final String ERROR_ROOM_FAILED = "CHAT_INVALID";
    private static final String ERROR_NOT_ADMIN = "CHAT_ADMIN_REQUIRED";
    // Avatars are numbered by their hash, which stays below this bit, so files never share a number with one.
    private static final long MEDIA_ID_BASE = 1L << 62;
    private static final long DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final long DOWNLOAD_POLL_MS = 250;
    private static final long SESSION_TIMEOUT_MS = 20000;
    private static final String ERROR_SEND_FAILED = "COGRAM_SEND_FAILED";

    private final Context context = ApplicationLoader.applicationContext;
    private final SharedPreferences preferences = context.getSharedPreferences("cogram_xmpp", Activity.MODE_PRIVATE);
    private final XmppIdMap ids = new XmppIdMap(context);
    private final ExecutorService events = Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncPending = new AtomicBoolean();
    // The engine holds its listeners weakly, so a listener nothing else refers to stops being called.
    private final XmppConnectionService.OnConversationUpdate conversationListener = this::scheduleSync;
    private final XmppConnectionService.OnRosterUpdate rosterListener = this::scheduleSync;
    private final ArrayList<Runnable> waiting = new ArrayList<>();
    // Waits for encryption keys one message at a time, so a chat's messages keep their order.
    private final ExecutorService outbox = Executors.newSingleThreadExecutor();
    private final Map<Long, PendingSend> pendingSends = new LinkedHashMap<>();
    // Messages the engine deleted, which Telegram learns about with the next sync.
    private final Set<Integer> pendingDeletes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // What Telegram last received of each message's text and reactions, so changes are sent as edits.
    private final Map<Integer, String> snapshots = new HashMap<>();
    // Called from the engine's threads, so it takes no lock of this class, which calls into the engine.
    private final XmppConnectionService.OnMessageChange messageChange = new XmppConnectionService.OnMessageChange() {
        @Override
        public void onMessageReplaced(Message message, String previousUuid) {
            ids.replaceUuid(previousUuid, message.getUuid());
        }

        @Override
        public void onMessageDeleted(String uuid) {
            int id = ids.findMessage(uuid);
            if (id != 0) {
                pendingDeletes.add(id);
                scheduleSync();
            }
        }

        @Override
        public void onRoomMemberChanged(Conversation room, Jid member, boolean wasMember, boolean isMember) {
            memberChanges.add(new RoomMemberChange(room, member, isMember));
            scheduleSync();
        }
    };

    private static final class RoomMemberChange {
        final Conversation room;
        final Jid member;
        final boolean joined;

        RoomMemberChange(Conversation room, Jid member, boolean joined) {
            this.room = room;
            this.member = member;
            this.joined = joined;
        }
    }
    private final java.util.Queue<RoomMemberChange> memberChanges = new java.util.concurrent.ConcurrentLinkedQueue<>();
    // Room members who are not contacts, so Telegram knows who wrote what.
    private final Map<Long, TLRPC.User> roomUsers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Integer> readOutboxMax = new HashMap<>();
    // Telegram knows photos by number, and the engine knows avatars by their hash.
    private final Map<Long, String> avatarsByPhotoId = new HashMap<>();
    // What Telegram last received about each user, so only changes are sent again.
    private final Map<Long, String> sentUsers = new HashMap<>();
    private final Set<Long> onlinePeers = new HashSet<>();
    private final long selfId;
    private TLRPC.User self;
    private volatile XmppCalls calls;

    private final XmppCalls.Peers callPeers = new XmppCalls.Peers() {
        @Override
        public long peerId(Jid address) {
            return ids.peerId(address.asBareJid().toString());
        }

        @Override
        public String address(long peerId) {
            return ids.peerJid(peerId);
        }

        @Override
        public void push(TLRPC.Updates updates, long peerId) {
            UpdatesListener target;
            synchronized (XmppBackend.this) {
                TLRPC.User user = user(peerId);
                if (user != null) {
                    updates.users.add(user);
                }
                target = listener;
            }
            if (target != null) {
                target.onUpdates(updates);
            }
        }
    };
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
        selfId = ids.peerId(address.asBareJid().toString());
        self = createUser(selfId, address.getLocal(), address.getLocal(), null, onlineStatus(), true);
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
        connected.setOnConversationListChangedListener(conversationListener);
        connected.setOnRosterUpdateListener(rosterListener);
        connected.setOnMessageChangeListener(messageChange);
        Account account;
        synchronized (this) {
            account = account();
        }
        if (account != null) {
            calls = new XmppCalls(connected, account, selfId, callPeers);
        }
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
        ArrayList<Runnable> finishedSends = new ArrayList<>();
        UpdatesListener target;
        synchronized (this) {
            target = listener;
            // A first sync after sign-in numbers the whole archive, which only one commit keeps fast.
            ids.inTransaction(() -> {
                collectFinishedSends(finishedSends);
                collectUpdates(updates);
                return null;
            });
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("cogram: sync updates = " + updates.size() + " pts = " + pts + " listener = " + (target != null));
        }
        for (Runnable finished : finishedSends) {
            finished.run();
        }
        if (target != null) {
            for (TLRPC.Updates update : updates) {
                target.onUpdates(update);
            }
        }
    }

    /** A message Telegram shows as sending until the engine reports what became of it. */
    private static final class PendingSend {
        final Message message;
        final long randomId;
        final Supplier<TLObject> response;
        final DeferredReply reply = new DeferredReply();
        volatile boolean failed;

        PendingSend(Message message, long randomId, Supplier<TLObject> response) {
            this.message = message;
            this.randomId = randomId;
            this.response = response;
        }
    }

    private PendingSend track(Message message, long randomId, Supplier<TLObject> response) {
        PendingSend send = new PendingSend(message, randomId, response);
        pendingSends.put(randomId, send);
        ids.rememberSend(randomId, message.getUuid());
        return send;
    }

    private void failSend(PendingSend send) {
        send.failed = true;
        scheduleSync();
    }

    /** Answers the sends that ended, first of all so their pts come before those of other updates. */
    private void collectFinishedSends(List<Runnable> finished) {
        for (Iterator<PendingSend> iterator = pendingSends.values().iterator(); iterator.hasNext(); ) {
            PendingSend send = iterator.next();
            int status = send.message.getStatus();
            boolean delivered = status == Message.STATUS_SEND || status == Message.STATUS_SEND_RECEIVED
                    || status == Message.STATUS_SEND_DISPLAYED;
            boolean rejected = status == Message.STATUS_SEND_FAILED;
            if (!delivered && !rejected && !send.failed) {
                continue;
            }
            iterator.remove();
            ids.forgetSend(send.randomId);
            if (delivered) {
                TLObject response = send.response.get();
                finished.add(() -> send.reply.complete(response, null));
                continue;
            }
            if (rejected) {
                dropFailedMessage(send.message);
            }
            TLRPC.TL_error error = new TLRPC.TL_error();
            error.code = 400;
            error.text = ERROR_SEND_FAILED;
            finished.add(() -> send.reply.complete(null, error));
        }
    }

    /** Telegram keeps the failed message and offers to resend it, which makes a new one here. */
    private void dropFailedMessage(Message message) {
        Account account = account();
        if (account != null) {
            int id = ids.findMessage(message.getUuid());
            account.getXmppConnection().getManager(ModerationManager.class).deleteLocally(message);
            pendingDeletes.remove(id);
        }
    }

    /** Finds a message Telegram is resending, usually because the app closed while it was going out. */
    private PendingSend resumeSend(long userId, long randomId) {
        PendingSend running = pendingSends.get(randomId);
        if (running != null) {
            return running;
        }
        Message message = findMessage(ids.sendUuid(randomId));
        if (message == null || message.getStatus() == Message.STATUS_SEND_FAILED) {
            return null;
        }
        int id = ids.findMessage(message.getUuid());
        int date = (int) (message.getTimeSent() / 1000);
        TLRPC.MessageMedia media = storedMedia(id);
        boolean room = isRoom(message);
        PendingSend send = track(message, randomId, () -> room ? roomSent(message, userId, randomId)
                : media != null ? mediaSent(userId, id, date, media, randomId) : textSent(id, date));
        scheduleSync();
        return send;
    }

    /**
     * Encrypts as Conversations does, with OMEMO unless the contact publishes none. Conversations
     * first builds the sessions from its chat screen, so the first message needs that done here,
     * and the wait is off the gateway's threads. A message is never sent bare because keys failed.
     */
    private void whenEncryptionDecided(Conversation conversation, Message message, Runnable failed, Runnable send) {
        outbox.execute(() -> {
            int encryption = conversation.getNextEncryption();
            if (encryption == Message.ENCRYPTION_AXOLOTL) {
                AxolotlService.SessionReadiness readiness = conversation.getAccount().getAxolotlService()
                        .prepareSessions(conversation, SESSION_TIMEOUT_MS);
                if (readiness == AxolotlService.SessionReadiness.FAILED) {
                    failed.run();
                    return;
                }
                if (readiness == AxolotlService.SessionReadiness.NO_OMEMO) {
                    encryption = Message.ENCRYPTION_NONE;
                }
            }
            message.setEncryption(encryption);
            send.run();
        });
    }

    private TLRPC.TL_updateShortSentMessage textSent(int id, int date) {
        pts++;
        savePts();
        TLRPC.TL_updateShortSentMessage sent = new TLRPC.TL_updateShortSentMessage();
        sent.flags = TLObject.FLAG_1;
        sent.out = true;
        sent.id = id;
        sent.pts = pts;
        sent.pts_count = 1;
        sent.date = date;
        return sent;
    }

    private TLRPC.Updates mediaSent(long userId, int id, int date, TLRPC.MessageMedia media, long randomId) {
        TLRPC.TL_message tl = new TLRPC.TL_message();
        tl.id = id;
        tl.out = true;
        tl.date = date;
        tl.message = "";
        tl.peer_id = peerUser(userId);
        tl.from_id = peerUser(selfId);
        tl.dialog_id = userId;
        tl.flags = TLObject.FLAG_8 | TLObject.FLAG_9;
        tl.media = media;
        pts++;
        savePts();
        ids.setMessagePts(id, pts);

        TL_update.TL_updateMessageID messageId = new TL_update.TL_updateMessageID();
        messageId.id = id;
        messageId.random_id = randomId;
        TL_update.TL_updateNewMessage newMessage = new TL_update.TL_updateNewMessage();
        newMessage.message = tl;
        newMessage.pts = pts;
        newMessage.pts_count = 1;
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates.add(messageId);
        updates.updates.add(newMessage);
        updates.date = now();
        return updates;
    }

    private void storeSentMedia(int id, TLRPC.MessageMedia media) {
        SerializedData out = new SerializedData(media.getObjectSize());
        media.serializeToStream(out);
        ids.putSentMedia(id, out.toByteArray());
    }

    private TLRPC.MessageMedia storedMedia(int id) {
        byte[] stored = ids.sentMedia(id);
        if (stored == null) {
            return null;
        }
        SerializedData data = new SerializedData(stored);
        return TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false);
    }

    private void collectUpdates(ArrayList<TLRPC.Updates> updates) {
        {
            for (RoomMemberChange change = memberChanges.poll(); change != null; change = memberChanges.poll()) {
                updates.add(channelMessageUpdate(memberChangeMessage(change)));
            }
            for (Conversation conversation : chats()) {
                long peerId = peerId(conversation);
                int readMax = 0;
                if (isRoom(conversation) && ids.findMessage(ROOM_CREATED_PREFIX + conversation.getAddress().asBareJid()) == 0) {
                    updates.add(channelMessageUpdate(roomCreatedMessage(conversation)));
                }
                for (Message message : loadedMessages(conversation)) {
                    int known = ids.findMessage(message.getUuid());
                    if (known == 0 && !(isMedia(message) && isDownloading(message))) {
                        updates.add(createNewMessageUpdate(message, peerId));
                        snapshots.put(ids.findMessage(message.getUuid()), snapshot(message));
                    } else if (known != 0) {
                        TLRPC.Updates change = changeUpdate(message, known, peerId);
                        if (change != null) {
                            updates.add(change);
                        }
                    }
                    if (message.getStatus() == Message.STATUS_SEND_DISPLAYED) {
                        readMax = Math.max(readMax, ids.messageId(message.getUuid(), message.getTimeSent()));
                    }
                }
                Integer known = readOutboxMax.get(peerId);
                if (readMax > (known == null ? 0 : known)) {
                    readOutboxMax.put(peerId, readMax);
                    updates.add(createReadOutboxUpdate(peerId, readMax, isRoom(conversation)));
                    if (known != null) {
                        // Seen happening now, unlike reads found at start that happened while the app was closed.
                        ids.addRead(peerId, readMax, now());
                    }
                }
            }
            if (!pendingDeletes.isEmpty()) {
                ArrayList<Integer> deleted = new ArrayList<>(pendingDeletes);
                pendingDeletes.removeAll(deleted);
                updates.addAll(deleteUpdates(deleted));
            }
            for (Contact contact : pendingRequestsWithoutChat()) {
                if (ids.findMessage(CONTACT_REQUEST_PREFIX + contact.getAddress().asBareJid()) == 0) {
                    updates.add(newMessageUpdate(contactRequestMessage(contact)));
                }
            }
            ArrayList<TLRPC.User> changed = new ArrayList<>();
            for (TLRPC.User user : users()) {
                String snapshot = snapshotOf(user);
                String sent = sentUsers.put(user.id, snapshot);
                if (sent != null && !sent.equals(snapshot)) {
                    changed.add(user);
                }
            }
            if (!changed.isEmpty()) {
                updates.add(createUsersUpdate(changed));
            }
        }
    }

    private TLRPC.Updates createNewMessageUpdate(Message message, long peerId) {
        TLRPC.Message tl = toTl(message, peerId);
        if (isRoom(message)) {
            return channelMessageUpdate(tl);
        }
        pts++;
        savePts();
        ids.setMessagePts(tl.id, pts);
        if (tl instanceof TLRPC.TL_messageService || !(tl.media instanceof TLRPC.TL_messageMediaEmpty)) {
            // A short update carries only text, so call entries and files travel as whole messages.
            return wholeMessageUpdate(tl);
        }
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

    private TLRPC.Updates newMessageUpdate(TLRPC.Message tl) {
        pts++;
        savePts();
        ids.setMessagePts(tl.id, pts);
        return wholeMessageUpdate(tl);
    }

    private TLRPC.Updates wholeMessageUpdate(TLRPC.Message tl) {
        TL_update.TL_updateNewMessage newMessage = new TL_update.TL_updateNewMessage();
        newMessage.message = tl;
        newMessage.pts = pts;
        newMessage.pts_count = 1;
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates.add(newMessage);
        updates.date = now();
        return updates;
    }

    private static TLRPC.Updates createReadOutboxUpdate(long peerId, int maxId, boolean room) {
        if (room) {
            TL_update.TL_updateReadChannelOutbox read = new TL_update.TL_updateReadChannelOutbox();
            read.channel_id = peerId;
            read.max_id = maxId;
            TLRPC.TL_updateShort update = new TLRPC.TL_updateShort();
            update.update = read;
            return update;
        }
        TL_update.TL_updateReadHistoryOutbox read = new TL_update.TL_updateReadHistoryOutbox();
        read.peer = peerUser(peerId);
        read.max_id = maxId;
        TLRPC.TL_updateShort update = new TLRPC.TL_updateShort();
        update.update = read;
        return update;
    }

    /** The parts of a message an edit, a finished download or a reaction can change. */
    private static String snapshot(Message message) {
        StringBuilder snapshot = new StringBuilder(contentSnapshot(message));
        for (eu.siacs.conversations.entities.Reaction reaction : message.getReactions()) {
            snapshot.append('\u0000').append(reaction.normalizedReaction()).append(reaction.received);
        }
        return snapshot.toString();
    }

    /**
     * The engine rewrites a file message's body while it moves the file. None of that concerns
     * Telegram, which already holds a file we sent and only needs telling when one we received
     * has arrived; another edit would replace Telegram's file with a copy to download again.
     */
    private static String contentSnapshot(Message message) {
        if (!isMedia(message)) {
            return String.valueOf(message.getBody());
        }
        if (message.getStatus() != Message.STATUS_RECEIVED) {
            return "";
        }
        return message.isFileOrImage() && !isDownloading(message) ? "fetched" : "fetching";
    }

    /** Returns an edit or reactions update when the message changed since Telegram last got it. */
    private TLRPC.Updates changeUpdate(Message message, int id, long peerId) {
        String snapshot = snapshot(message);
        String previous = snapshots.put(id, snapshot);
        if (previous == null || previous.equals(snapshot)) {
            return null;
        }
        if (!previous.split("\u0000", -1)[0].equals(contentSnapshot(message))) {
            return editUpdate(toTl(message, peerId));
        }
        TL_update.TL_updateMessageReactions reactions = new TL_update.TL_updateMessageReactions();
        reactions.peer = peerOf(peerId, isRoom(message));
        reactions.msg_id = id;
        reactions.reactions = reactionsOf(message);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates.add(reactions);
        updates.date = now();
        return updates;
    }

    private TLRPC.Updates editUpdate(TLRPC.Message tl) {
        if (tl.peer_id.channel_id != 0) {
            TL_update.TL_updateEditChannelMessage edit = new TL_update.TL_updateEditChannelMessage();
            edit.message = tl;
            edit.pts = nextChannelPts(tl.peer_id.channel_id);
            edit.pts_count = 1;
            return roomUpdates(tl.peer_id.channel_id, edit);
        }
        pts++;
        savePts();
        TL_update.TL_updateEditMessage edit = new TL_update.TL_updateEditMessage();
        edit.message = tl;
        edit.pts = pts;
        edit.pts_count = 1;
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates.add(edit);
        updates.date = now();
        return updates;
    }

    private List<TLRPC.Updates> deleteUpdates(List<Integer> deleted) {
        LinkedHashMap<Long, ArrayList<Integer>> byChannel = new LinkedHashMap<>();
        for (int id : deleted) {
            byChannel.computeIfAbsent(ids.channelOf(id), key -> new ArrayList<>()).add(id);
        }
        ArrayList<TLRPC.Updates> result = new ArrayList<>();
        for (Map.Entry<Long, ArrayList<Integer>> entry : byChannel.entrySet()) {
            if (entry.getKey() == 0) {
                result.add(deleteUpdate(entry.getValue()));
                continue;
            }
            TL_update.TL_updateDeleteChannelMessages delete = new TL_update.TL_updateDeleteChannelMessages();
            delete.channel_id = entry.getKey();
            delete.messages.addAll(entry.getValue());
            for (int a = 0; a < entry.getValue().size(); a++) {
                delete.pts = nextChannelPts(entry.getKey());
            }
            delete.pts_count = entry.getValue().size();
            result.add(roomUpdates(entry.getKey(), delete));
        }
        return result;
    }

    private TLRPC.Updates deleteUpdate(List<Integer> deleted) {
        pts += deleted.size();
        savePts();
        TL_update.TL_updateDeleteMessages delete = new TL_update.TL_updateDeleteMessages();
        delete.messages.addAll(deleted);
        delete.pts = pts;
        delete.pts_count = deleted.size();
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates.add(delete);
        updates.date = now();
        return updates;
    }

    /** Reactions counted by emoji, with ours marked chosen so Telegram lets us take them back. */
    private static TLRPC.TL_messageReactions reactionsOf(Message message) {
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
        for (eu.siacs.conversations.entities.Reaction reaction : message.getReactions()) {
            int[] count = counts.get(reaction.normalizedReaction());
            if (count == null) {
                count = new int[2];
                counts.put(reaction.normalizedReaction(), count);
            }
            count[0]++;
            if (!reaction.received) {
                count[1] = 1;
            }
        }
        TLRPC.TL_messageReactions reactions = new TLRPC.TL_messageReactions();
        int order = 0;
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            TLRPC.TL_reactionCount count = new TLRPC.TL_reactionCount();
            TLRPC.TL_reactionEmoji emoji = new TLRPC.TL_reactionEmoji();
            emoji.emoticon = entry.getKey();
            count.reaction = emoji;
            count.count = entry.getValue()[0];
            if (entry.getValue()[1] != 0) {
                count.chosen = true;
                count.chosen_order = order++;
                count.flags |= TLObject.FLAG_0;
            }
            reactions.results.add(count);
        }
        return reactions;
    }

    /**
     * Conversations replies by quoting the message in "> " lines above the answer. The quote is
     * matched to the message it came from, so Telegram shows a reply header instead of the quote.
     */
    private static final class Quote {
        final String quoted;
        final String text;

        Quote(String quoted, String text) {
            this.quoted = quoted;
            this.text = text;
        }

        static Quote of(String body) {
            if (body == null || !body.startsWith(">")) {
                return null;
            }
            String[] lines = body.split("\n", -1);
            StringBuilder quoted = new StringBuilder();
            int line = 0;
            while (line < lines.length && lines[line].startsWith(">")) {
                String content = lines[line].substring(1);
                if (content.startsWith(" ")) {
                    content = content.substring(1);
                }
                if (quoted.length() > 0) {
                    quoted.append('\n');
                }
                quoted.append(content);
                line++;
            }
            StringBuilder text = new StringBuilder();
            for (; line < lines.length; line++) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(lines[line]);
            }
            String answer = text.toString().trim();
            String quote = quoted.toString().trim();
            return answer.isEmpty() || quote.isEmpty() ? null : new Quote(quote, answer);
        }
    }

    /** The text Telegram shows for a message, without a quote it replies with. */
    private static String shownText(Message message) {
        Quote quote = Quote.of(message.getBody());
        return quote == null ? message.getBody() : quote.text;
    }

    /** Finds the newest earlier message in the chat whose text the quote is taken from. */
    private int quotedMessageId(Message message, Quote quote) {
        if (!(message.getConversation() instanceof Conversation)) {
            return 0;
        }
        List<Message> messages = loadedMessages((Conversation) message.getConversation());
        for (int a = messages.size() - 1; a >= 0; a--) {
            Message candidate = messages.get(a);
            if (candidate.getTimeSent() >= message.getTimeSent() || candidate.getType() != Message.TYPE_TEXT) {
                continue;
            }
            String text = shownText(candidate);
            if (text != null && (text.trim().equals(quote.quoted) || text.trim().startsWith(quote.quoted))) {
                return ids.messageId(candidate.getUuid(), candidate.getTimeSent());
            }
        }
        return 0;
    }

    private void savePts() {
        preferences.edit().putInt("pts", pts).apply();
    }

    private Account account() {
        List<Account> accounts = service.getAccounts();
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private List<Conversation> chats() {
        ArrayList<Conversation> all = new ArrayList<>();
        service.populateWithOrderedConversations(all);
        return all;
    }

    private static boolean isRoom(Conversation conversation) {
        return conversation.getMode() == Conversation.MODE_MULTI;
    }

    private static boolean isRoom(Message message) {
        return message.getConversation().getMode() == Conversation.MODE_MULTI;
    }

    private static TLRPC.Peer peerChannel(long channelId) {
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = channelId;
        return peer;
    }

    private static TLRPC.Peer peerOf(long peerId, boolean room) {
        return room ? peerChannel(peerId) : peerUser(peerId);
    }

    /** Each room counts its own updates, as Telegram expects of a supergroup. */
    private int channelPts(long channelId) {
        return preferences.getInt(PREFERENCE_CHANNEL_PTS + channelId, 1);
    }

    private int nextChannelPts(long channelId) {
        int next = channelPts(channelId) + 1;
        preferences.edit().putInt(PREFERENCE_CHANNEL_PTS + channelId, next).apply();
        return next;
    }

    /**
     * The sender of a room message as a Telegram user. A private room gives their real address; a
     * public one only a nick, so they become a user that exists in that room alone and cannot be
     * written to or linked to the same person elsewhere.
     */
    private long senderId(Message message) {
        Jid real = message.getTrueCounterpart();
        Jid counterpart = message.getCounterpart();
        String nick = counterpart == null ? null : counterpart.getResource();
        if (real != null) {
            TLRPC.User user = memberUser(real.asBareJid(), nick, false);
            roomUsers.put(user.id, user);
            return user.id;
        }
        Jid address = counterpart != null ? counterpart : ((Conversation) message.getConversation()).getAddress();
        return anonymousUser(address, nick).id;
    }

    private TLRPC.User anonymousUser(Jid address, String nick) {
        long id = ids.peerId(address.toString());
        TLRPC.User user = createUser(id, TextUtils.isEmpty(nick) ? address.getLocal() : nick, null, null, new TLRPC.TL_userStatusRecently(), false);
        user.contact = false;
        user.mutual_contact = false;
        roomUsers.put(id, user);
        return user;
    }

    /** A member who may be neither a contact nor anyone we ever wrote to. Nothing is added to the roster. */
    private TLRPC.User memberUser(Jid real, String nick, boolean online) {
        long id = ids.peerId(real.toString());
        if (id == selfId) {
            return selfUser();
        }
        Contact contact = account().getRoster().getContact(real);
        TLRPC.User user = contactUser(contact);
        if (!contact.showInContactList()) {
            if (!TextUtils.isEmpty(nick)) {
                user.first_name = distinctName(nick, real);
            }
            user.status = online ? onlineStatus() : new TLRPC.TL_userStatusRecently();
        }
        return user;
    }

    /** Anyone can pick any nick, so one matching a contact's name shows whose it really is. */
    private String distinctName(String nick, Jid real) {
        for (Contact contact : rosterContacts()) {
            if (nick.equalsIgnoreCase(contact.getDisplayName())) {
                return nick + " (" + real.getLocal() + ")";
            }
        }
        return nick;
    }

    private TLRPC.User memberUser(MucOptions.User member) {
        boolean online = member.getFullJid() != null;
        String nick = member.resource();
        if (member.getRealJid() != null) {
            TLRPC.User user = memberUser(member.getRealJid(), nick, online);
            roomUsers.put(user.id, user);
            return user;
        }
        return anonymousUser(member.getFullJid(), nick);
    }

    private static boolean isSelf(MucOptions.User member) {
        return member instanceof MucOptions.Self || member.realJidMatchesAccount();
    }

    private static boolean isAdmin(Affiliation affiliation) {
        return affiliation == Affiliation.OWNER || affiliation == Affiliation.ADMIN;
    }

    /** XMPP has a single admin role, so an admin holds every right a room can actually enforce. */
    private static TLRPC.TL_chatAdminRights adminRights(boolean owner) {
        TLRPC.TL_chatAdminRights rights = new TLRPC.TL_chatAdminRights();
        rights.change_info = true;
        rights.delete_messages = true;
        rights.ban_users = true;
        rights.invite_users = true;
        rights.add_admins = owner;
        rights.other = true;
        return rights;
    }

    private static TLRPC.TL_chatBannedRights roomDefaultRights() {
        TLRPC.TL_chatBannedRights rights = new TLRPC.TL_chatBannedRights();
        rights.send_polls = true;
        rights.change_info = true;
        rights.invite_users = true;
        rights.pin_messages = true;
        rights.manage_topics = true;
        return rights;
    }

    private String roomTitle(Conversation room) {
        String name = room.getMucOptions().getName();
        return TextUtils.isEmpty(name) ? String.valueOf(room.getName()) : name;
    }

    /** A room as the supergroup Telegram's screens work with. */
    private TLRPC.Chat roomChat(Conversation room) {
        MucOptions options = room.getMucOptions();
        Affiliation affiliation = options.getSelf().getAffiliation();
        long id = peerId(room);
        TLRPC.TL_channel chat = new TLRPC.TL_channel();
        chat.id = id;
        chat.access_hash = id;
        chat.title = roomTitle(room);
        int created = ids.findMessage(ROOM_CREATED_PREFIX + room.getAddress().asBareJid());
        chat.date = created == 0 ? now() : (int) (ids.messageTime(created) / 1000);
        chat.megagroup = true;
        chat.creator = affiliation == Affiliation.OWNER;
        // A lost connection also sets an error, but only these mean we are out of the room.
        MucOptions.Error error = options.getError();
        chat.left = error == MucOptions.Error.KICKED || error == MucOptions.Error.BANNED
                || error == MucOptions.Error.MEMBERS_ONLY || error == MucOptions.Error.DESTROYED;
        chat.participants_count = roomMembers(room).size();
        chat.default_banned_rights = roomDefaultRights();
        chat.flags = TLObject.FLAG_8 | TLObject.FLAG_13 | TLObject.FLAG_17 | TLObject.FLAG_18;
        if (isAdmin(affiliation)) {
            chat.admin_rights = adminRights(chat.creator);
            chat.flags |= TLObject.FLAG_14;
        }
        String avatar = options.getAvatar();
        if (avatar != null && FileBackend.getAvatarFile(context, avatar).exists()) {
            TLRPC.TL_chatPhoto photo = new TLRPC.TL_chatPhoto();
            photo.photo_id = photoId(avatar);
            photo.dc_id = PHOTO_DC_ID;
            SerializedData data = serialize(photo);
            chat.photo = TLRPC.ChatPhoto.TLdeserialize(data, data.readInt32(false), false);
        } else {
            chat.photo = new TLRPC.TL_chatPhotoEmpty();
        }
        return chat;
    }

    /** Everyone in a room once, including us and members who are offline right now. */
    private List<MucOptions.User> roomMembers(Conversation room) {
        LinkedHashMap<String, MucOptions.User> members = new LinkedHashMap<>();
        MucOptions options = room.getMucOptions();
        members.put("self", options.getSelf());
        for (MucOptions.User user : options.getUsers()) {
            Jid real = user.getRealJid();
            if (user.realJidMatchesAccount()) {
                continue;
            }
            if (real != null) {
                members.put(real.toString(), user);
            } else if (user.getFullJid() != null) {
                members.put(user.getFullJid().toString(), user);
            }
        }
        return new ArrayList<>(members.values());
    }

    /** The message that starts a room's chat, since Telegram shows no dialog without a top message. */
    private TLRPC.Message roomCreatedMessage(Conversation room) {
        long peerId = peerId(room);
        List<Message> messages = loadedMessages(room);
        long time = messages.isEmpty() ? System.currentTimeMillis() : messages.get(0).getTimeSent() - 1;
        int id = ids.messageId(ROOM_CREATED_PREFIX + room.getAddress().asBareJid(), time);
        TLRPC.TL_messageActionChannelCreate action = new TLRPC.TL_messageActionChannelCreate();
        action.title = roomTitle(room);
        return roomServiceMessage(id, (int) (ids.messageTime(id) / 1000), peerId, action);
    }

    private TLRPC.Message roomServiceMessage(int id, int date, long channelId, TLRPC.MessageAction action) {
        TLRPC.TL_messageService tl = new TLRPC.TL_messageService();
        tl.id = id;
        tl.date = date;
        tl.peer_id = peerChannel(channelId);
        tl.from_id = peerUser(selfId);
        tl.dialog_id = -channelId;
        tl.action = action;
        tl.flags = TLObject.FLAG_8;
        return tl;
    }

    private TLRPC.Message memberChangeMessage(RoomMemberChange change) {
        long channelId = peerId(change.room);
        TLRPC.User member = memberUser(change.member, null, false);
        roomUsers.put(member.id, member);
        long time = System.currentTimeMillis();
        int id = ids.messageId("room-member:" + java.util.UUID.randomUUID(), time);
        TLRPC.MessageAction action;
        if (change.joined) {
            action = new TLRPC.TL_messageActionChatAddUser();
            action.users.add(member.id);
        } else {
            action = new TLRPC.TL_messageActionChatDeleteUser();
            action.user_id = member.id;
        }
        TLRPC.Message tl = roomServiceMessage(id, (int) (time / 1000), channelId, action);
        tl.from_id = peerUser(member.id);
        return tl;
    }

    private TLRPC.TL_updates roomUpdates(long channelId, TLRPC.Update update) {
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates.add(update);
        updates.date = now();
        Conversation room = conversation(channelId);
        if (room != null) {
            updates.chats.add(roomChat(room));
        }
        return updates;
    }

    private TLRPC.TL_updates channelMessageUpdate(TLRPC.Message tl) {
        long channelId = tl.peer_id.channel_id;
        int channelPts = nextChannelPts(channelId);
        ids.setMessagePts(tl.id, channelPts, channelId);
        TL_update.TL_updateNewChannelMessage update = new TL_update.TL_updateNewChannelMessage();
        update.message = tl;
        update.pts = channelPts;
        update.pts_count = 1;
        return roomUpdates(channelId, update);
    }

    /** The answer to a send in a room, which unlike a one-to-one send carries the whole message. */
    private TLRPC.Updates roomSent(Message message, long channelId, long randomId) {
        TLRPC.TL_updates updates = channelMessageUpdate(toTl(message, channelId));
        TL_update.TL_updateMessageID messageId = new TL_update.TL_updateMessageID();
        messageId.id = updates.updates.get(0) instanceof TL_update.TL_updateNewChannelMessage
                ? ((TL_update.TL_updateNewChannelMessage) updates.updates.get(0)).message.id : 0;
        messageId.random_id = randomId;
        updates.updates.add(0, messageId);
        return updates;
    }

    private long peerId(Conversation conversation) {
        return ids.peerId(conversation.getAddress().asBareJid().toString());
    }

    private Conversation conversation(long peerId) {
        String jid = ids.peerJid(peerId);
        // An address with a resource is an anonymous room member, who cannot be written to.
        if (jid == null || account() == null || Jid.of(jid).getResource() != null) {
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
        return message.getType() != Message.TYPE_STATUS;
    }

    private static String textOf(Message message) {
        switch (message.getEncryption()) {
            case Message.ENCRYPTION_PGP:
            case Message.ENCRYPTION_DECRYPTION_FAILED:
            case Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE:
            case Message.ENCRYPTION_AXOLOTL_FAILED:
                return TEXT_UNREADABLE;
            default:
                if (message.getBody() != null && message.getBody().matches("null\\|\\d+")) {
                    // An older engine lost the link of a file while checking its size.
                    return TEXT_FILE;
                }
                return message.getType() == Message.TYPE_TEXT ? message.getBody() : TEXT_FILE;
        }
    }

    private TLRPC.Message toTl(Message message, long peerId) {
        if (message.getType() == Message.TYPE_RTP_SESSION) {
            return callToTl(message, peerId);
        }
        boolean out = message.getStatus() != Message.STATUS_RECEIVED;
        TLRPC.TL_message tl = new TLRPC.TL_message();
        tl.id = ids.messageId(message.getUuid(), message.getTimeSent());
        tl.out = out;
        tl.date = (int) (message.getTimeSent() / 1000);
        tl.message = textOf(message);
        boolean room = isRoom(message);
        tl.peer_id = peerOf(peerId, room);
        tl.from_id = peerUser(out ? selfId : room ? senderId(message) : peerId);
        tl.dialog_id = room ? -peerId : peerId;
        tl.media = new TLRPC.TL_messageMediaEmpty();
        tl.flags = TLObject.FLAG_8 | TLObject.FLAG_9;
        if (isMedia(message)) {
            tl.message = "";
            TLRPC.MessageMedia sent = out ? storedMedia(tl.id) : null;
            tl.media = sent != null ? sent : media(message, tl.id, tl.date);
        } else if (message.getType() == Message.TYPE_TEXT && !TEXT_UNREADABLE.equals(tl.message)) {
            Quote quote = Quote.of(message.getBody());
            int replyTo = quote == null ? 0 : quotedMessageId(message, quote);
            if (replyTo != 0) {
                tl.message = quote.text;
                TLRPC.TL_messageReplyHeader header = new TLRPC.TL_messageReplyHeader();
                header.reply_to_msg_id = replyTo;
                header.flags |= TLObject.FLAG_4;
                tl.reply_to = header;
                tl.flags |= TLObject.FLAG_3;
            }
        }
        if (message.edited()) {
            tl.edit_date = tl.date;
            tl.flags |= TLObject.FLAG_15;
        }
        if (!message.getReactions().isEmpty()) {
            tl.reactions = reactionsOf(message);
            tl.flags |= TLObject.FLAG_20;
        }
        // What Telegram now holds, so a later download, edit or reaction reaches it as an edit.
        snapshots.putIfAbsent(tl.id, snapshot(message));
        return tl;
    }

    /** A file or a link to one, which Conversations sends as the message's whole body. */
    private static boolean isMedia(Message message) {
        if (TEXT_UNREADABLE.equals(textOf(message))) {
            return false;
        }
        // While a file downloads its body is "link|size", which no longer reads as a link.
        return message.isFileOrImage() || message.treatAsDownloadable() || message.getTransferable() != null
                || message.isOOb() && message.getFileParams().url != null;
    }

    /** The engine fetches small files on arrival, and a message shows once it can show the file too. */
    private static boolean isDownloading(Message message) {
        Transferable transferable = message.getTransferable();
        if (transferable == null) {
            return false;
        }
        int status = transferable.getStatus();
        return status == Transferable.STATUS_CHECKING || status == Transferable.STATUS_DOWNLOADING;
    }

    private static long mediaId(int messageId) {
        return MEDIA_ID_BASE | (messageId & 0xffffffffL);
    }

    static boolean isMediaId(long id) {
        return (id & MEDIA_ID_BASE) != 0;
    }

    /**
     * Files become Telegram documents, with voice messages and pictures marked so that Telegram's
     * voice player and photo cells show them. Pictures not yet downloaded stay documents, since
     * Telegram needs a picture's size before it can lay out a photo.
     */
    private TLRPC.MessageMedia media(Message message, int messageId, int date) {
        File file = service.getFileBackend().getFile(message);
        boolean local = file.exists() && !isDownloading(message);
        Message.FileParams params = message.getFileParams();
        String mime = message.getMimeType();
        if (mime == null && params.url != null) {
            // A body of "link|size" hides the extension from the engine, but the link still has it.
            mime = MimeUtils.guessMimeTypeFromExtension(MimeUtils.extractRelevantExtension(params.url));
        }
        if (mime == null) {
            mime = "application/octet-stream";
        }
        long size = local ? file.length() : params.getSize();
        int width = params.width;
        int height = params.height;
        if (local && mime.startsWith("image/") && (width == 0 || height == 0)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            width = bounds.outWidth;
            height = bounds.outHeight;
        }
        long id = mediaId(messageId);
        TLRPC.MessageMedia media;
        if (local && width > 0 && height > 0 && (mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/webp"))) {
            TLRPC.TL_photo photo = new TLRPC.TL_photo();
            photo.id = id;
            photo.access_hash = id;
            photo.file_reference = new byte[0];
            photo.date = date;
            photo.dc_id = PHOTO_DC_ID;
            TLRPC.TL_photoSize photoSize = new TLRPC.TL_photoSize();
            photoSize.type = "y";
            photoSize.w = width;
            photoSize.h = height;
            photoSize.size = (int) size;
            photo.sizes.add(photoSize);
            TLRPC.TL_messageMediaPhoto photoMedia = new TLRPC.TL_messageMediaPhoto();
            photoMedia.photo = photo;
            photoMedia.flags = TLObject.FLAG_0;
            media = photoMedia;
        } else {
            TLRPC.TL_document document = new TLRPC.TL_document();
            document.id = id;
            document.access_hash = id;
            document.file_reference = new byte[0];
            document.date = date;
            document.mime_type = mime;
            document.size = size;
            document.dc_id = PHOTO_DC_ID;
            String name = fileName(message, file);
            TLRPC.TL_documentAttributeFilename fileName = new TLRPC.TL_documentAttributeFilename();
            fileName.file_name = name;
            document.attributes.add(fileName);
            int runtime = params.runtime;
            if (runtime == 0 && local && (mime.startsWith("audio/") || mime.startsWith("video/"))) {
                runtime = mediaRuntime(file);
            }
            int duration = (runtime + 999) / 1000;
            if (mime.startsWith("audio/")) {
                TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
                audio.voice = isVoice(message, mime);
                audio.duration = duration;
                audio.flags = audio.voice ? TLObject.FLAG_10 : 0;
                document.attributes.add(audio);
            } else if (mime.startsWith("video/")) {
                TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
                video.duration = duration;
                video.w = width;
                video.h = height;
                document.attributes.add(video);
            }
            TLRPC.TL_messageMediaDocument documentMedia = new TLRPC.TL_messageMediaDocument();
            documentMedia.document = document;
            documentMedia.flags = TLObject.FLAG_0;
            media = documentMedia;
        }
        // Telegram's parser fills in the file locations its loaders use.
        SerializedData data = serialize(media);
        return TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false);
    }

    /** The name as sent comes first, since Telegram finds its copy of a file by the name's extension. */
    private static String fileName(Message message, File file) {
        String url = message.getFileParams().url;
        if (url != null) {
            // The fragment of an aesgcm link is the file's key, which must never become its name.
            String path = Uri.parse(url).getLastPathSegment();
            if (!TextUtils.isEmpty(path)) {
                return path;
            }
        }
        return file.exists() ? file.getName() : message.getUuid();
    }

    /** Conversations names its recordings this way, and other clients send voice as Ogg Opus. */
    private static boolean isVoice(Message message, String mime) {
        // The name as sent, since a downloaded file is stored under the engine's own name.
        String url = message.getFileParams().url;
        String sent = url == null ? "" : String.valueOf(Uri.parse(url).getLastPathSegment()).toLowerCase(Locale.ROOT);
        if (sent.startsWith("recording") || sent.startsWith("voice")) {
            return true;
        }
        // Recorders write these formats, while music comes as mp3, flac or wav and keeps the player.
        return mime.equals("audio/ogg") || mime.equals("audio/opus") || mime.equals("audio/mp4") || mime.equals("audio/m4a")
                || mime.equals("audio/x-m4a") || mime.equals("audio/aac") || mime.equals("audio/amr")
                || mime.equals("audio/3gpp") || mime.equals("audio/webm");
    }

    /** The length of a recording in milliseconds, for files sent without it. */
    private static int mediaRuntime(File file) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration == null ? 0 : Integer.parseInt(duration);
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignore) {
            }
        }
    }

    /** The engine logs each call as a message, which Telegram shows as a call entry in the chat and the calls list. */
    private TLRPC.Message callToTl(Message message, long peerId) {
        boolean out = message.getStatus() != Message.STATUS_RECEIVED;
        RtpSessionStatus status = RtpSessionStatus.of(message.getBody());
        TLRPC.TL_messageActionPhoneCall action = new TLRPC.TL_messageActionPhoneCall();
        action.call_id = ids.messageId(message.getUuid(), message.getTimeSent());
        if (status.successful) {
            action.duration = (int) (status.duration / 1000);
            action.flags |= TLObject.FLAG_1;
        } else {
            action.reason = new TLRPC.TL_phoneCallDiscardReasonMissed();
            action.flags |= TLObject.FLAG_0;
        }
        TLRPC.TL_messageService tl = new TLRPC.TL_messageService();
        tl.id = (int) action.call_id;
        tl.out = out;
        tl.date = (int) (message.getTimeSent() / 1000);
        tl.peer_id = peerUser(peerId);
        tl.from_id = peerUser(out ? selfId : peerId);
        tl.dialog_id = peerId;
        tl.action = action;
        tl.flags = TLObject.FLAG_8;
        return tl;
    }

    private static TLRPC.Peer peerUser(long userId) {
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = userId;
        return peer;
    }

    /** The username is the address without its server, which is how the profile shows it. */
    private TLRPC.User createUser(long id, String name, String username, String avatar, TLRPC.UserStatus status, boolean isSelf) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.access_hash = id;
        user.first_name = name;
        user.last_name = "";
        user.username = username;
        user.status = status;
        user.self = isSelf;
        user.contact = !isSelf;
        user.mutual_contact = !isSelf;
        user.flags = TLObject.FLAG_0 | TLObject.FLAG_1 | TLObject.FLAG_3 | TLObject.FLAG_6;
        if (avatar != null) {
            user.photo = profilePhoto(avatar);
            user.flags |= TLObject.FLAG_5;
        }
        return user;
    }

    private TLRPC.User contactUser(Conversation conversation) {
        return contactUser(conversation.getContact());
    }

    private TLRPC.User contactUser(Contact contact) {
        long peerId = peerId(contact);
        TLRPC.User user = createUser(peerId, contact.getDisplayName(), contact.getAddress().getLocal(), contact.getAvatar(), statusOf(contact, peerId), false);
        user.contact = contact.showInContactList();
        user.mutual_contact = contact.getOption(Contact.Options.TO) && contact.getOption(Contact.Options.FROM);
        return user;
    }

    private long peerId(Contact contact) {
        return ids.peerId(contact.getAddress().asBareJid().toString());
    }

    /** The roster entry for a peer. The engine makes one on demand, without starting a chat. */
    private Contact contact(long peerId) {
        String jid = ids.peerJid(peerId);
        Account account = service == null ? null : account();
        if (jid == null || account == null || Jid.of(jid).getResource() != null) {
            return null;
        }
        return account.getRoster().getContact(Jid.of(jid));
    }

    private static boolean hasPendingRequest(Contact contact) {
        return contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST);
    }

    /** The people Telegram lists as contacts, and those waiting for an answer to their contact request. */
    private List<Contact> rosterContacts() {
        ArrayList<Contact> contacts = new ArrayList<>();
        Account account = account();
        if (account == null) {
            return contacts;
        }
        for (Contact contact : account.getRoster().getContacts()) {
            if (contact.showInContactList() || hasPendingRequest(contact)) {
                contacts.add(contact);
            }
        }
        return contacts;
    }

    /** A contact request has no message in XMPP, so it appears as a service message that opens the chat. */
    private TLRPC.Message contactRequestMessage(Contact contact) {
        long peerId = peerId(contact);
        int id = ids.messageId(CONTACT_REQUEST_PREFIX + contact.getAddress().asBareJid(), System.currentTimeMillis());
        TLRPC.TL_messageActionCustomAction action = new TLRPC.TL_messageActionCustomAction();
        action.message = LocaleController.getString(R.string.PhogramContactRequest);
        TLRPC.TL_messageService tl = new TLRPC.TL_messageService();
        tl.id = id;
        tl.date = (int) (ids.messageTime(id) / 1000);
        tl.peer_id = peerUser(peerId);
        tl.from_id = peerUser(peerId);
        tl.dialog_id = peerId;
        tl.action = action;
        tl.flags = TLObject.FLAG_8;
        return tl;
    }

    private TLRPC.User accountUser(Account account) {
        String name = account.getDisplayName();
        if (TextUtils.isEmpty(name)) {
            name = account.getJid().getLocal();
        }
        return createUser(selfId, name, account.getJid().getLocal(), account.getAvatar(), onlineStatus(), true);
    }

    private static TLRPC.UserStatus onlineStatus() {
        TLRPC.TL_userStatusOnline online = new TLRPC.TL_userStatusOnline();
        online.expires = now() + ONLINE_EXPIRES_SECONDS;
        return online;
    }

    /** XMPP keeps no last-seen time, so it is the moment this app last saw the contact go offline. */
    private TLRPC.UserStatus statusOf(Contact contact, long peerId) {
        String key = PREFERENCE_LAST_SEEN + peerId;
        if (!contact.getPresences().isEmpty()) {
            onlinePeers.add(peerId);
            return onlineStatus();
        }
        if (onlinePeers.remove(peerId)) {
            preferences.edit().putInt(key, now()).apply();
        }
        int lastSeen = preferences.getInt(key, 0);
        if (lastSeen == 0) {
            return new TLRPC.TL_userStatusRecently();
        }
        TLRPC.TL_userStatusOffline offline = new TLRPC.TL_userStatusOffline();
        // Telegram keeps an offline user's last-seen time in the expires field.
        offline.expires = lastSeen;
        return offline;
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    private static String snapshotOf(TLRPC.User user) {
        long photoId = user.photo == null ? 0 : user.photo.photo_id;
        boolean online = user.status instanceof TLRPC.TL_userStatusOnline;
        return user.first_name + "\n" + user.username + "\n" + photoId + "\n" + online;
    }

    private static TLRPC.Updates createUsersUpdate(List<TLRPC.User> users) {
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        for (TLRPC.User user : users) {
            TL_update.TL_updateUser photo = new TL_update.TL_updateUser();
            photo.user_id = user.id;
            updates.updates.add(photo);

            TL_update.TL_updateUserStatus status = new TL_update.TL_updateUserStatus();
            status.user_id = user.id;
            status.status = user.status;
            updates.updates.add(status);

            TL_update.TL_updateUserName name = new TL_update.TL_updateUserName();
            name.user_id = user.id;
            name.first_name = user.first_name;
            name.last_name = user.last_name;
            TLRPC.TL_username username = new TLRPC.TL_username();
            username.username = user.username;
            username.active = true;
            name.usernames.add(username);
            updates.updates.add(name);
        }
        updates.users.addAll(users);
        updates.date = now();
        return updates;
    }

    private long photoId(String avatar) {
        long id;
        try {
            id = Long.parseLong(avatar.substring(0, Math.min(15, avatar.length())), 16);
        } catch (NumberFormatException e) {
            id = avatar.hashCode() & 0x7fffffffL;
        }
        id = Math.max(id, 1);
        avatarsByPhotoId.put(id, avatar);
        return id;
    }

    /** Built through Telegram's own parser, which fills in the file locations its image loader uses. */
    private TLRPC.UserProfilePhoto profilePhoto(String avatar) {
        TLRPC.TL_userProfilePhoto photo = new TLRPC.TL_userProfilePhoto();
        photo.photo_id = photoId(avatar);
        photo.dc_id = PHOTO_DC_ID;
        SerializedData data = serialize(photo);
        return TLRPC.UserProfilePhoto.TLdeserialize(data, data.readInt32(false), false);
    }

    private TLRPC.Photo photo(String avatar) {
        File file = FileBackend.getAvatarFile(context, avatar);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int side = Math.max(bounds.outWidth, PHOTO_SMALL_SIDE);

        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = photoId(avatar);
        photo.access_hash = photo.id;
        photo.file_reference = new byte[0];
        photo.date = (int) (file.lastModified() / 1000);
        photo.dc_id = PHOTO_DC_ID;
        photo.sizes.add(photoSize("a", PHOTO_SMALL_SIDE, (int) file.length()));
        photo.sizes.add(photoSize("c", side, (int) file.length()));
        SerializedData data = serialize(photo);
        return TLRPC.Photo.TLdeserialize(data, data.readInt32(false), false);
    }

    private static TLRPC.PhotoSize photoSize(String type, int side, int size) {
        TLRPC.TL_photoSize photoSize = new TLRPC.TL_photoSize();
        photoSize.type = type;
        photoSize.w = side;
        photoSize.h = side;
        photoSize.size = size;
        return photoSize;
    }

    private static SerializedData serialize(TLObject object) {
        SerializedData out = new SerializedData(object.getObjectSize());
        object.serializeToStream(out);
        return new SerializedData(out.toByteArray());
    }

    private String avatarOf(long userId) {
        if (userId == selfId) {
            Account account = account();
            return account == null ? null : account.getAvatar();
        }
        Contact contact = contact(userId);
        return contact == null ? null : contact.getAvatar();
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
    public synchronized TLRPC.User selfUser() {
        Account account = service == null ? null : account();
        if (account != null) {
            self = accountUser(account);
        }
        return self;
    }

    @Override
    public synchronized List<TLRPC.User> users() {
        LinkedHashMap<Long, TLRPC.User> users = new LinkedHashMap<>();
        TLRPC.User me = selfUser();
        users.put(me.id, me);
        if (service != null) {
            for (Conversation conversation : chats()) {
                if (isRoom(conversation)) {
                    for (MucOptions.User member : roomMembers(conversation)) {
                        if (!isSelf(member)) {
                            memberUser(member);
                        }
                    }
                    continue;
                }
                TLRPC.User user = contactUser(conversation);
                users.put(user.id, user);
            }
            users.putAll(roomUsers);
            for (Contact contact : rosterContacts()) {
                TLRPC.User user = contactUser(contact);
                users.put(user.id, user);
            }
        }
        return new ArrayList<>(users.values());
    }

    @Override
    public synchronized TLRPC.User user(long userId) {
        if (userId == selfId) {
            return selfUser();
        }
        TLRPC.User member = roomUsers.get(userId);
        if (member != null) {
            return member;
        }
        Contact contact = contact(userId);
        return contact == null ? null : contactUser(contact);
    }

    @Override
    public synchronized String address(long userId) {
        if (userId == selfId) {
            Account account = service == null ? null : account();
            return account == null ? firstAccountAddress(context).asBareJid().toString() : account.getJid().asBareJid().toString();
        }
        return ids.peerJid(userId);
    }

    @Override
    public synchronized List<TLRPC.TL_contact> contacts() {
        ArrayList<TLRPC.TL_contact> contacts = new ArrayList<>();
        if (service == null) {
            return contacts;
        }
        for (Contact contact : rosterContacts()) {
            if (contact.showInContactList()) {
                TLRPC.TL_contact tl = new TLRPC.TL_contact();
                tl.user_id = peerId(contact);
                tl.mutual = contact.getOption(Contact.Options.TO) && contact.getOption(Contact.Options.FROM);
                contacts.add(tl);
            }
        }
        return contacts;
    }

    /** Finds contacts by name or address, and anyone at all when the query is a full user@server address. */
    @Override
    public synchronized List<TLRPC.User> search(String query) {
        LinkedHashMap<Long, TLRPC.User> found = new LinkedHashMap<>();
        if (service == null || account() == null) {
            return new ArrayList<>();
        }
        String address = CogramSignIn.parseAddress(query);
        if (address != null && !address.equals(account().getJid().asBareJid().toString())) {
            TLRPC.User user = user(ids.peerId(address));
            if (user != null) {
                found.put(user.id, user);
            }
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (!needle.isEmpty()) {
            for (Contact contact : rosterContacts()) {
                String name = String.valueOf(contact.getDisplayName()).toLowerCase(Locale.ROOT);
                String jid = contact.getAddress().asBareJid().toString().toLowerCase(Locale.ROOT);
                if (name.contains(needle) || jid.contains(needle)) {
                    TLRPC.User user = contactUser(contact);
                    found.put(user.id, user);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Resolves a full address, or a bare username when exactly one contact has it. */
    @Override
    public synchronized TLRPC.User resolve(String username) {
        if (service == null || account() == null) {
            return null;
        }
        String address = CogramSignIn.parseAddress(username);
        if (address != null) {
            return user(ids.peerId(address));
        }
        TLRPC.User match = null;
        for (Contact contact : rosterContacts()) {
            if (username.equalsIgnoreCase(contact.getAddress().getLocal())) {
                if (match != null) {
                    return null;
                }
                match = contactUser(contact);
            }
        }
        return match;
    }

    /** Adds the peer to the roster and asks to see their presence, accepting their own request if they sent one. */
    @Override
    public synchronized TLRPC.User addContact(long userId, String firstName, String lastName) {
        Contact contact = contact(userId);
        if (contact == null) {
            return null;
        }
        String name = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        if (!name.isEmpty()) {
            contact.setServerName(name);
        }
        if (hasPendingRequest(contact)) {
            contact.resetOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST);
            account().getXmppConnection().getManager(PresenceManager.class).subscribed(contact.getAddress().asBareJid());
        }
        service.createContact(contact);
        return contactUser(contact);
    }

    @Override
    public synchronized void deleteContact(long userId) {
        Contact contact = contact(userId);
        if (contact != null) {
            service.deleteContactOnServer(contact);
        }
    }

    /** Blocking also turns down a pending contact request, as Telegram's block button does for strangers. */
    @Override
    public synchronized void setBlocked(long userId, boolean blocked) {
        Contact contact = contact(userId);
        if (contact == null) {
            return;
        }
        if (!blocked) {
            service.sendUnblockRequest(contact);
            return;
        }
        if (hasPendingRequest(contact)) {
            service.stopPresenceUpdatesTo(contact);
        }
        service.sendBlockRequest(contact, false, null);
    }

    /** Strangers and people asking to be added get Telegram's bar with "Add contact" and "Block". */
    @Override
    public synchronized TLRPC.PeerSettings peerSettings(long userId) {
        TLRPC.TL_peerSettings settings = new TLRPC.TL_peerSettings();
        Contact contact = userId == selfId ? null : contact(userId);
        if (contact != null && (!contact.showInContactList() || hasPendingRequest(contact))) {
            settings.add_contact = true;
            settings.block_contact = true;
            // Telegram decides whether to show the bar from the flags, which only serializing would set.
            settings.flags = TLObject.FLAG_1 | TLObject.FLAG_2;
        }
        return settings;
    }

    @Override
    public synchronized TLRPC.TL_upload_file photoFile(long photoId, long offset, int limit) {
        String avatar = avatarsByPhotoId.get(photoId);
        if (avatar == null) {
            return null;
        }
        File file = FileBackend.getAvatarFile(context, avatar);
        if (!file.exists()) {
            return null;
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            int length = (int) Math.max(0, Math.min(limit, input.length() - offset));
            byte[] bytes = new byte[length];
            input.seek(offset);
            input.readFully(bytes);
            NativeByteBuffer buffer = new NativeByteBuffer(length);
            buffer.buffer.put(bytes);
            buffer.buffer.position(0);
            TLRPC.TL_upload_file result = new TLRPC.TL_upload_file();
            result.type = new TLRPC.TL_storage_fileUnknown();
            result.mtime = (int) (file.lastModified() / 1000);
            result.bytes = buffer;
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private synchronized Message findMessage(String uuid) {
        if (service == null || uuid == null) {
            return null;
        }
        for (Conversation conversation : chats()) {
            Message message = conversation.findMessageWithUuid(uuid);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    /**
     * Serves part of a file in a chat, fetching it first when the engine has not, which can take a
     * while. It is not synchronized, so the rest of the gateway keeps working meanwhile.
     */
    @Override
    public TLRPC.TL_upload_file mediaFile(long mediaId, long offset, int limit) {
        Message message = findMessage(ids.messageUuid((int) (mediaId & 0xffffffffL)));
        if (message == null) {
            return null;
        }
        File file = service.getFileBackend().getFile(message);
        long deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS;
        boolean requested = false;
        while (!file.exists() || isDownloading(message)) {
            if (!requested && !isDownloading(message)) {
                service.getHttpConnectionManager().createNewDownloadConnection(message, true);
                requested = true;
            }
            Transferable transferable = message.getTransferable();
            if (System.currentTimeMillis() > deadline || requested && transferable != null
                    && (transferable.getStatus() == Transferable.STATUS_FAILED || transferable.getStatus() == Transferable.STATUS_CANCELLED)) {
                return null;
            }
            try {
                Thread.sleep(DOWNLOAD_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            file = service.getFileBackend().getFile(message);
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            int length = (int) Math.max(0, Math.min(limit, input.length() - offset));
            byte[] bytes = new byte[length];
            input.seek(offset);
            input.readFully(bytes);
            NativeByteBuffer buffer = new NativeByteBuffer(length);
            buffer.buffer.put(bytes);
            buffer.buffer.position(0);
            TLRPC.TL_upload_file result = new TLRPC.TL_upload_file();
            result.type = new TLRPC.TL_storage_fileUnknown();
            result.mtime = (int) (file.lastModified() / 1000);
            result.bytes = buffer;
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Sends a file Telegram uploaded through the engine's HTTP upload, encrypted with OMEMO when the
     * chat's text would be. Telegram gets the message at once, with the file it already holds.
     */
    @Override
    public synchronized TLObject sendFile(long userId, File file, String mime, List<TLRPC.DocumentAttribute> attributes, boolean photo, long randomId) {
        PendingSend resumed = resumeSend(userId, randomId);
        if (resumed != null) {
            file.delete();
            return resumed.reply;
        }
        Conversation conversation = conversation(userId);
        if (conversation == null) {
            file.delete();
            return null;
        }
        Message message = new Message(conversation, "", Message.ENCRYPTION_NONE);
        message.setCounterpart(conversation.getNextCounterpart());
        message.setType(photo ? Message.TYPE_IMAGE : Message.TYPE_FILE);
        int id = ids.messageId(message.getUuid(), message.getTimeSent());
        int date = (int) (message.getTimeSent() / 1000);
        // Read before the engine takes the file, since it deletes the upload once copied.
        TLRPC.MessageMedia media = sentMedia(id, date, file, mime, attributes, photo);
        storeSentMedia(id, media);
        snapshots.put(id, snapshot(message));
        boolean room = isRoom(conversation);
        PendingSend send = track(message, randomId, () -> room ? roomSent(message, userId, randomId) : mediaSent(userId, id, date, media, randomId));
        whenEncryptionDecided(conversation, message, () -> {
            file.delete();
            failSend(send);
        }, () -> service.attachFile(message, Uri.fromFile(file), mime, file::delete, () -> failSend(send)));
        return send.reply;
    }

    private static TLRPC.MessageMedia sentMedia(int messageId, int date, File file, String mime, List<TLRPC.DocumentAttribute> attributes, boolean photo) {
        long id = mediaId(messageId);
        TLRPC.MessageMedia media;
        if (photo) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            TLRPC.TL_photo tlPhoto = new TLRPC.TL_photo();
            tlPhoto.id = id;
            tlPhoto.access_hash = id;
            tlPhoto.file_reference = new byte[0];
            tlPhoto.date = date;
            tlPhoto.dc_id = PHOTO_DC_ID;
            TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
            size.type = "y";
            size.w = bounds.outWidth;
            size.h = bounds.outHeight;
            size.size = (int) file.length();
            tlPhoto.sizes.add(size);
            TLRPC.TL_messageMediaPhoto photoMedia = new TLRPC.TL_messageMediaPhoto();
            photoMedia.photo = tlPhoto;
            photoMedia.flags = TLObject.FLAG_0;
            media = photoMedia;
        } else {
            TLRPC.TL_document document = new TLRPC.TL_document();
            document.id = id;
            document.access_hash = id;
            document.file_reference = new byte[0];
            document.date = date;
            document.mime_type = mime;
            document.size = file.length();
            document.dc_id = PHOTO_DC_ID;
            document.attributes.addAll(attributes);
            TLRPC.TL_messageMediaDocument documentMedia = new TLRPC.TL_messageMediaDocument();
            documentMedia.document = document;
            documentMedia.flags = TLObject.FLAG_0;
            media = documentMedia;
        }
        SerializedData data = serialize(media);
        return TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false);
    }

    @Override
    public synchronized TLRPC.UserFull fullUser(long userId) {
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = userId;
        // Telegram saves these over what it loaded for the chat's bar, so they must be the same.
        full.settings = peerSettings(userId);
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        String avatar = avatarOf(userId);
        if (avatar != null && FileBackend.getAvatarFile(context, avatar).exists()) {
            full.profile_photo = photo(avatar);
            full.flags |= TLObject.FLAG_2;
        }
        Contact contact = userId == selfId ? null : contact(userId);
        if (contact != null && calls != null) {
            // Telegram shows its call buttons only when the contact's client can answer.
            RtpCapability.Capability capability = XmppCalls.capability(contact);
            full.phone_calls_available = capability != RtpCapability.Capability.NONE;
            full.video_calls_available = capability == RtpCapability.Capability.VIDEO;
        }
        return full;
    }

    @Override
    public XmppCalls calls() {
        return calls;
    }

    @Override
    public synchronized List<TLRPC.Message> callHistory(int offsetId, int limit) {
        ArrayList<TLRPC.Message> history = new ArrayList<>();
        if (service == null) {
            return history;
        }
        for (Conversation conversation : chats()) {
            long peerId = peerId(conversation);
            for (Message message : service.databaseBackend.getMessages(conversation, CALL_HISTORY_SCAN, -1)) {
                if (message.getType() == Message.TYPE_RTP_SESSION) {
                    TLRPC.Message call = toTl(message, peerId);
                    if (offsetId == 0 || call.id < offsetId) {
                        history.add(call);
                    }
                }
            }
        }
        Collections.sort(history, (a, b) -> Integer.compare(b.id, a.id));
        return history.size() > limit ? new ArrayList<>(history.subList(0, limit)) : history;
    }

    @Override
    public synchronized List<TLRPC.Photo> userPhotos(long userId) {
        ArrayList<TLRPC.Photo> photos = new ArrayList<>();
        String avatar = avatarOf(userId);
        if (avatar != null && FileBackend.getAvatarFile(context, avatar).exists()) {
            photos.add(photo(avatar));
        }
        return photos;
    }

    @Override
    public TLRPC.User updateName(String firstName, String lastName) {
        Account account;
        synchronized (this) {
            account = account();
        }
        if (account != null) {
            String name = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
            account.setDisplayName(name);
            service.databaseBackend.updateAccount(account);
            service.publishDisplayName(account);
        }
        return selfUser();
    }

    /** Publishes the photo as the account's avatar, and waits so Telegram receives the new photo. */
    @Override
    public TLRPC.Photo updatePhoto(byte[] image) {
        Account account;
        synchronized (this) {
            account = account();
        }
        if (account == null) {
            return null;
        }
        String previous = account.getAvatar();
        File file = new File(context.getCacheDir(), "cogram_profile_photo.jpg");
        CountDownLatch published = new CountDownLatch(1);
        boolean[] succeeded = new boolean[1];
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(image);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        // Telegram shows profile photos to everybody by default, so the avatar is published openly.
        service.publishAvatar(account, Uri.fromFile(file), true, new OnAvatarPublication() {
            @Override
            public void onAvatarPublicationSucceeded() {
                succeeded[0] = true;
                published.countDown();
            }

            @Override
            public void onAvatarPublicationFailed(int res) {
                published.countDown();
            }
        });
        String avatar = null;
        try {
            published.await(AVATAR_PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // The account takes the new avatar when the server echoes the publication back.
            for (int waited = 0; succeeded[0] && waited < AVATAR_ECHO_WAIT_MS; waited += AVATAR_ECHO_POLL_MS) {
                avatar = account.getAvatar();
                if (avatar != null && !avatar.equals(previous)) {
                    break;
                }
                Thread.sleep(AVATAR_ECHO_POLL_MS);
            }
        } catch (InterruptedException e) {
            return null;
        } finally {
            file.delete();
        }
        if (!succeeded[0] || avatar == null) {
            return null;
        }
        synchronized (this) {
            return photo(avatar);
        }
    }

    @Override
    public synchronized List<TLRPC.Dialog> dialogs() {
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
        for (Conversation conversation : chats()) {
            List<Message> messages = loadedMessages(conversation);
            boolean room = isRoom(conversation);
            if (messages.isEmpty() && !room) {
                continue;
            }
            long peerId = peerId(conversation);
            int top = messages.isEmpty() ? roomCreatedMessage(conversation).id
                    : ids.messageId(messages.get(messages.size() - 1).getUuid(), messages.get(messages.size() - 1).getTimeSent());
            int readOutbox = 0;
            for (Message message : messages) {
                if (message.getStatus() == Message.STATUS_SEND_DISPLAYED) {
                    readOutbox = Math.max(readOutbox, ids.messageId(message.getUuid(), message.getTimeSent()));
                }
            }
            Message firstUnread = conversation.getFirstUnreadMessage();
            TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
            dialog.id = room ? -peerId : peerId;
            dialog.peer = peerOf(peerId, room);
            if (room) {
                dialog.pts = channelPts(peerId);
                dialog.flags |= TLObject.FLAG_0;
            }
            dialog.top_message = top;
            dialog.read_inbox_max_id = firstUnread == null ? top : ids.messageId(firstUnread.getUuid(), firstUnread.getTimeSent()) - 1;
            dialog.read_outbox_max_id = readOutbox;
            dialog.unread_count = conversation.unreadCount();
            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
            dialogs.add(dialog);
        }
        for (Contact contact : pendingRequestsWithoutChat()) {
            TLRPC.Message request = contactRequestMessage(contact);
            TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
            dialog.id = request.dialog_id;
            dialog.peer = peerUser(request.dialog_id);
            dialog.top_message = request.id;
            dialog.read_inbox_max_id = request.id - 1;
            dialog.unread_count = 1;
            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
            dialogs.add(dialog);
        }
        return dialogs;
    }

    /** People asking to be added who have not written yet, whose chat would otherwise not exist. */
    private List<Contact> pendingRequestsWithoutChat() {
        ArrayList<Contact> pending = new ArrayList<>();
        if (service == null) {
            return pending;
        }
        for (Contact contact : rosterContacts()) {
            if (!hasPendingRequest(contact)) {
                continue;
            }
            Conversation conversation = service.find(account(), contact.getAddress().asBareJid());
            if (conversation == null || loadedMessages(conversation).isEmpty()) {
                pending.add(contact);
            }
        }
        return pending;
    }

    @Override
    public synchronized List<TLRPC.Message> topMessages() {
        ArrayList<TLRPC.Message> tops = new ArrayList<>();
        for (Conversation conversation : chats()) {
            List<Message> messages = loadedMessages(conversation);
            if (!messages.isEmpty()) {
                tops.add(toTl(messages.get(messages.size() - 1), peerId(conversation)));
            } else if (isRoom(conversation)) {
                tops.add(roomCreatedMessage(conversation));
            }
        }
        for (Contact contact : pendingRequestsWithoutChat()) {
            tops.add(contactRequestMessage(contact));
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
        ArrayList<TLRPC.Message> newestFirst = ids.inTransaction(() -> {
            ArrayList<TLRPC.Message> converted = new ArrayList<>();
            for (Message message : service.databaseBackend.getMessages(conversation, scan, before)) {
                if (isShown(message)) {
                    converted.add(toTl(message, userId));
                }
            }
            return converted;
        });
        if (newestFirst.isEmpty() && isRoom(conversation)) {
            newestFirst.add(roomCreatedMessage(conversation));
        } else if (newestFirst.isEmpty() && hasPendingRequest(conversation.getContact())) {
            newestFirst.add(contactRequestMessage(conversation.getContact()));
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

    /**
     * A reply goes out the way Conversations writes one, with the replied text quoted above, which
     * every XMPP client shows and which turns back into Telegram's reply header here.
     */
    @Override
    public synchronized TLObject sendText(long userId, String text, int replyToId, long randomId) {
        PendingSend resumed = resumeSend(userId, randomId);
        if (resumed != null) {
            return resumed.reply;
        }
        Conversation conversation = conversation(userId);
        if (conversation == null) {
            return null;
        }
        Message replied = replyToId == 0 ? null : engineMessage(replyToId);
        String body = text;
        if (replied != null && replied.getType() == Message.TYPE_TEXT && shownText(replied) != null) {
            StringBuilder quoted = new StringBuilder();
            for (String line : shownText(replied).split("\n")) {
                quoted.append("> ").append(line).append('\n');
            }
            body = quoted + text;
        }
        Message message = new Message(conversation, body, Message.ENCRYPTION_NONE);
        int id = ids.messageId(message.getUuid(), message.getTimeSent());
        int date = (int) (message.getTimeSent() / 1000);
        boolean room = isRoom(conversation);
        PendingSend send = track(message, randomId, () -> room ? roomSent(message, userId, randomId) : textSent(id, date));
        whenEncryptionDecided(conversation, message, () -> failSend(send), () -> service.sendMessage(message));
        return send.reply;
    }

    /** The engine's message behind a Telegram id, from memory or else the engine's database. */
    private Message engineMessage(int id) {
        String uuid = ids.messageUuid(id);
        if (uuid == null) {
            return null;
        }
        Message message = findMessage(uuid);
        if (message != null) {
            return message;
        }
        for (Conversation conversation : chats()) {
            message = service.databaseBackend.getMessageWithUuidOrRemoteId(conversation, uuid);
            if (message != null && uuid.equals(message.getUuid())) {
                return message;
            }
        }
        return null;
    }

    private long peerOf(Message message) {
        return ids.peerId(message.getConversation().getAddress().asBareJid().toString());
    }

    /** Corrects one of our text messages (XEP-0308), which the other side shows as edited. */
    @Override
    public synchronized TLRPC.Updates editText(long userId, int id, String text) {
        Message message = engineMessage(id);
        if (message == null || message.getStatus() == Message.STATUS_RECEIVED || message.getType() != Message.TYPE_TEXT
                || !(message.getConversation() instanceof Conversation)) {
            return null;
        }
        Quote quote = Quote.of(message.getBody());
        String body = text;
        if (quote != null && quotedMessageId(message, quote) != 0) {
            // A reply keeps its quote, which is what marks it as a reply.
            body = message.getBody().substring(0, message.getBody().length() - quote.text.length()) + text;
        }
        String previousUuid = message.getUuid();
        message.putEdited(new Message.BodyVersion(body, message.getEncryption(), null));
        service.updateMessage(message, previousUuid);
        service.sendMessage(message);
        snapshots.put(id, snapshot(message));
        TLRPC.Message tl = toTl(message, userId);
        tl.edit_date = now();
        return editUpdate(tl);
    }

    /** Sets our reactions to a message (XEP-0444), replacing the ones we gave it before. */
    @Override
    public synchronized TLRPC.Updates react(long userId, int id, List<String> emojis) {
        Message message = engineMessage(id);
        Account account = account();
        if (message == null || account == null) {
            return null;
        }
        account.getXmppConnection().getManager(ReactionManager.class).sendReactions(message, emojis);
        snapshots.put(id, snapshot(message));
        TL_update.TL_updateMessageReactions reactions = new TL_update.TL_updateMessageReactions();
        reactions.peer = peerOf(userId, isRoom(message));
        reactions.msg_id = id;
        reactions.reactions = reactionsOf(message);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates.add(reactions);
        updates.date = now();
        return updates;
    }

    /**
     * Deletes messages here, and for our own messages also on the other side when Telegram asks to
     * delete for both (XEP-0424). XMPP lets no one retract a message someone else sent.
     */
    @Override
    public synchronized TLRPC.TL_messages_affectedMessages deleteMessages(List<Integer> messageIds, boolean revoke) {
        deleteEngineMessages(messageIds, revoke);
        pts += messageIds.size();
        savePts();
        TLRPC.TL_messages_affectedMessages affected = new TLRPC.TL_messages_affectedMessages();
        affected.pts = pts;
        affected.pts_count = messageIds.size();
        return affected;
    }

    private void deleteEngineMessages(List<Integer> messageIds, boolean revoke) {
        Account account = account();
        ModerationManager moderation = account == null ? null : account.getXmppConnection().getManager(ModerationManager.class);
        for (int id : messageIds) {
            Message message = engineMessage(id);
            if (message == null || moderation == null) {
                continue;
            }
            if (revoke && message.getStatus() != Message.STATUS_RECEIVED) {
                moderation.retractOwn(message);
            } else if (revoke && isRoom(message) && canModerate((Conversation) message.getConversation())
                    && message.getServerMsgId() != null) {
                moderation.moderate(message);
            } else {
                moderation.deleteLocally(message);
            }
            snapshots.remove(id);
        }
        // Telegram removes them itself once this returns.
        pendingDeletes.removeAll(messageIds);
    }

    private static boolean canModerate(Conversation room) {
        MucOptions options = room.getMucOptions();
        return options.moderation() && isAdmin(options.getSelf().getAffiliation());
    }

    @Override
    public synchronized TLRPC.TL_messages_affectedMessages deleteRoomMessages(long channelId, List<Integer> messageIds) {
        deleteEngineMessages(messageIds, true);
        int channelPts = channelPts(channelId);
        for (int a = 0; a < messageIds.size(); a++) {
            channelPts = nextChannelPts(channelId);
        }
        TLRPC.TL_messages_affectedMessages affected = new TLRPC.TL_messages_affectedMessages();
        affected.pts = channelPts;
        affected.pts_count = messageIds.size();
        return affected;
    }

    @Override
    public synchronized List<TLRPC.Chat> roomChats() {
        ArrayList<TLRPC.Chat> rooms = new ArrayList<>();
        if (service != null) {
            for (Conversation conversation : chats()) {
                if (isRoom(conversation)) {
                    rooms.add(roomChat(conversation));
                }
            }
        }
        return rooms;
    }

    private Conversation room(long channelId) {
        Conversation conversation = conversation(channelId);
        return conversation != null && isRoom(conversation) ? conversation : null;
    }

    @Override
    public synchronized TLRPC.TL_messages_chatFull fullRoom(long channelId) {
        Conversation room = room(channelId);
        if (room == null) {
            return null;
        }
        MucOptions options = room.getMucOptions();
        List<MucOptions.User> members = roomMembers(room);
        int admins = 0;
        for (MucOptions.User member : members) {
            if (isAdmin(member.getAffiliation())) {
                admins++;
            }
        }
        List<Message> messages = loadedMessages(room);
        int top = messages.isEmpty() ? 0 : ids.messageId(messages.get(messages.size() - 1).getUuid(), messages.get(messages.size() - 1).getTimeSent());
        Message firstUnread = room.getFirstUnreadMessage();
        TLRPC.TL_channelFull full = new TLRPC.TL_channelFull();
        full.id = channelId;
        String about = options.getDescription();
        full.about = about == null ? "" : about;
        full.participants_count = members.size();
        full.admins_count = admins;
        full.read_inbox_max_id = firstUnread == null ? top : ids.messageId(firstUnread.getUuid(), firstUnread.getTimeSent()) - 1;
        full.read_outbox_max_id = top;
        full.unread_count = room.unreadCount();
        full.chat_photo = new TLRPC.TL_photoEmpty();
        String avatar = options.getAvatar();
        if (avatar != null && FileBackend.getAvatarFile(context, avatar).exists()) {
            full.chat_photo = photo(avatar);
        }
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        full.pts = channelPts(channelId);
        full.can_view_participants = true;
        full.available_reactions = new TLRPC.TL_chatReactionsAll();
        full.flags = TLObject.FLAG_0 | TLObject.FLAG_1 | TLObject.FLAG_3 | TLObject.FLAG_30;
        TLRPC.TL_messages_chatFull result = new TLRPC.TL_messages_chatFull();
        result.full_chat = full;
        result.chats.add(roomChat(room));
        result.users.addAll(users());
        return result;
    }

    private TLRPC.ChannelParticipant participant(MucOptions.User member, boolean selfOwner) {
        boolean self = isSelf(member);
        long id = self ? selfId : memberUser(member).id;
        TLRPC.ChannelParticipant participant;
        if (member.getAffiliation() == Affiliation.OWNER) {
            participant = new TLRPC.TL_channelParticipantCreator();
            participant.admin_rights = adminRights(true);
        } else if (member.getAffiliation() == Affiliation.ADMIN) {
            participant = new TLRPC.TL_channelParticipantAdmin();
            participant.admin_rights = adminRights(false);
            participant.can_edit = selfOwner;
            participant.promoted_by = selfId;
            participant.self = self;
            participant.date = now();
        } else if (self) {
            participant = new TLRPC.TL_channelParticipantSelf();
            participant.date = now();
        } else {
            participant = new TLRPC.TL_channelParticipant();
            participant.date = now();
        }
        participant.user_id = id;
        participant.peer = peerUser(id);
        return participant;
    }

    @Override
    public synchronized TLRPC.TL_channels_channelParticipants roomParticipants(long channelId, TLRPC.ChannelParticipantsFilter filter, int offset, int limit) {
        TLRPC.TL_channels_channelParticipants result = new TLRPC.TL_channels_channelParticipants();
        Conversation room = room(channelId);
        // XMPP gives no list of banned members to page through.
        if (room == null || filter instanceof TLRPC.TL_channelParticipantsKicked || filter instanceof TLRPC.TL_channelParticipantsBanned) {
            return result;
        }
        String query = filter instanceof TLRPC.TL_channelParticipantsSearch ? filter.q.toLowerCase(Locale.ROOT) : null;
        boolean selfOwner = room.getMucOptions().getSelf().getAffiliation() == Affiliation.OWNER;
        ArrayList<TLRPC.ChannelParticipant> matching = new ArrayList<>();
        for (MucOptions.User member : roomMembers(room)) {
            if (filter instanceof TLRPC.TL_channelParticipantsAdmins && !isAdmin(member.getAffiliation())) {
                continue;
            }
            TLRPC.ChannelParticipant participant = participant(member, selfOwner);
            if (query != null) {
                TLRPC.User user = user(participant.user_id);
                if (user == null || !(user.first_name + " " + user.username).toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
            }
            matching.add(participant);
        }
        result.count = matching.size();
        result.participants.addAll(matching.subList(Math.min(offset, matching.size()), Math.min(offset + limit, matching.size())));
        result.users.addAll(users());
        return result;
    }

    @Override
    public synchronized TLRPC.TL_channels_channelParticipant roomParticipant(long channelId, long userId) {
        Conversation room = room(channelId);
        if (room == null) {
            return null;
        }
        boolean selfOwner = room.getMucOptions().getSelf().getAffiliation() == Affiliation.OWNER;
        for (MucOptions.User member : roomMembers(room)) {
            TLRPC.ChannelParticipant participant = participant(member, selfOwner);
            if (participant.user_id == userId) {
                TLRPC.TL_channels_channelParticipant result = new TLRPC.TL_channels_channelParticipant();
                result.participant = participant;
                result.users.addAll(users());
                return result;
            }
        }
        return null;
    }

    @Override
    public synchronized TLObject roomDifference(long channelId, int fromPts) {
        Conversation room = room(channelId);
        int current = channelPts(channelId);
        if (room == null || fromPts >= current) {
            TLRPC.TL_updates_channelDifferenceEmpty empty = new TLRPC.TL_updates_channelDifferenceEmpty();
            empty.pts = current;
            empty.flags = TLObject.FLAG_0;
            empty.isFinal = true;
            return empty;
        }
        TLRPC.TL_updates_channelDifference difference = new TLRPC.TL_updates_channelDifference();
        difference.pts = current;
        difference.flags = TLObject.FLAG_0;
        difference.isFinal = true;
        Set<String> uuids = ids.channelMessagesAfterPts(channelId, fromPts);
        for (Message message : loadedMessages(room)) {
            if (uuids.contains(message.getUuid())) {
                difference.new_messages.add(toTl(message, channelId));
            }
        }
        difference.chats.add(roomChat(room));
        difference.users.addAll(users());
        return difference;
    }

    private static TLRPC.TL_error roomError(String text) {
        TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = 400;
        error.text = text;
        return error;
    }

    private Jid addressOf(long userId) {
        String jid = ids.peerJid(userId);
        return jid == null ? null : Jid.of(jid);
    }

    /** An encrypted room can't include someone who has no keys, or they would read nothing. */
    private boolean allHaveOmemo(Account account, List<Jid> members) {
        AxolotlService axolotl = account.getAxolotlService();
        for (Jid member : members) {
            if (!axolotl.publishesOmemo(member, SESSION_TIMEOUT_MS)) {
                return false;
            }
        }
        return true;
    }

    private static <T> void whenDone(ListenableFuture<T> future, java.util.function.Consumer<T> done, Runnable failed) {
        Futures.addCallback(future, new FutureCallback<T>() {
            @Override
            public void onSuccess(T result) {
                done.accept(result);
            }

            @Override
            public void onFailure(Throwable t) {
                FileLog.e(t);
                failed.run();
            }
        }, MoreExecutors.directExecutor());
    }

    private void finishWithRoom(DeferredReply reply, long channelId) {
        events.execute(() -> {
            TLRPC.TL_updates updates;
            synchronized (this) {
                updates = emptyRoomUpdates(channelId);
            }
            reply.complete(updates, null);
        });
    }

    private TLRPC.TL_updates emptyRoomUpdates(long channelId) {
        TL_update.TL_updateChannel update = new TL_update.TL_updateChannel();
        update.channel_id = channelId;
        TLRPC.TL_updates updates = roomUpdates(channelId, update);
        updates.users.addAll(users());
        return updates;
    }

    @Override
    public synchronized TLObject createRoom(String title, List<Long> userIds) {
        DeferredReply reply = new DeferredReply();
        Account account = account();
        if (account == null) {
            return null;
        }
        ArrayList<Jid> members = new ArrayList<>();
        for (long userId : userIds) {
            Jid address = addressOf(userId);
            if (address != null) {
                members.add(address.asBareJid());
            }
        }
        outbox.execute(() -> {
            if (!allHaveOmemo(account, members)) {
                reply.complete(null, roomError(ERROR_NO_OMEMO));
                return;
            }
            whenDone(account.getXmppConnection().getManager(MultiUserChatManager.class).createPrivateGroupChat(title, members),
                    created -> events.execute(() -> {
                        TLRPC.TL_messages_invitedUsers result = new TLRPC.TL_messages_invitedUsers();
                        synchronized (this) {
                            long channelId = peerId(created);
                            boolean announced = ids.findMessage(ROOM_CREATED_PREFIX + created.getAddress().asBareJid()) != 0;
                            result.updates = announced ? emptyRoomUpdates(channelId) : channelMessageUpdate(roomCreatedMessage(created));
                            result.updates.users.addAll(users());
                        }
                        reply.complete(result, null);
                    }),
                    () -> reply.complete(null, roomError(ERROR_ROOM_FAILED)));
        });
        return reply;
    }

    @Override
    public synchronized TLObject inviteToRoom(long channelId, List<Long> userIds) {
        Conversation room = room(channelId);
        Account account = account();
        if (room == null || account == null) {
            return null;
        }
        DeferredReply reply = new DeferredReply();
        ArrayList<Jid> members = new ArrayList<>();
        for (long userId : userIds) {
            Jid address = addressOf(userId);
            if (address != null) {
                members.add(address.asBareJid());
            }
        }
        outbox.execute(() -> {
            if (room.getNextEncryption() == Message.ENCRYPTION_AXOLOTL && !allHaveOmemo(account, members)) {
                reply.complete(null, roomError(ERROR_NO_OMEMO));
                return;
            }
            for (Jid member : members) {
                service.invite(room, member);
            }
            events.execute(() -> {
                TLRPC.TL_messages_invitedUsers result = new TLRPC.TL_messages_invitedUsers();
                synchronized (this) {
                    result.updates = emptyRoomUpdates(channelId);
                }
                reply.complete(result, null);
            });
        });
        return reply;
    }

    private TLObject changeAffiliation(long channelId, long userId, Affiliation affiliation) {
        Conversation room = room(channelId);
        Jid address = addressOf(userId);
        Account account = account();
        if (room == null || address == null || account == null) {
            return null;
        }
        DeferredReply reply = new DeferredReply();
        whenDone(account.getXmppConnection().getManager(MultiUserChatManager.class).setAffiliation(room, affiliation, address.asBareJid()),
                done -> finishWithRoom(reply, channelId),
                () -> reply.complete(null, roomError(ERROR_NOT_ADMIN)));
        return reply;
    }

    /** XMPP can only remove someone or let them back, so finer restrictions have no meaning here. */
    @Override
    public synchronized TLObject banFromRoom(long channelId, long userId, boolean banned) {
        return changeAffiliation(channelId, userId, banned ? Affiliation.OUTCAST : Affiliation.NONE);
    }

    @Override
    public synchronized TLObject setRoomAdmin(long channelId, long userId, boolean admin) {
        return changeAffiliation(channelId, userId, admin ? Affiliation.ADMIN : Affiliation.MEMBER);
    }

    @Override
    public synchronized TLObject leaveRoom(long channelId) {
        Conversation room = room(channelId);
        if (room == null) {
            return null;
        }
        TLRPC.TL_updates updates = emptyRoomUpdates(channelId);
        for (TLRPC.Chat chat : updates.chats) {
            chat.left = true;
        }
        service.archiveConversation(room);
        return updates;
    }

    @Override
    public synchronized TLObject destroyRoom(long channelId) {
        Conversation room = room(channelId);
        Account account = account();
        if (room == null || account == null) {
            return null;
        }
        DeferredReply reply = new DeferredReply();
        TLRPC.TL_updates updates = emptyRoomUpdates(channelId);
        whenDone(account.getXmppConnection().getManager(MultiUserChatManager.class).destroy(room.getAddress().asBareJid()),
                done -> {
                    service.archiveConversation(room);
                    reply.complete(updates, null);
                },
                () -> reply.complete(null, roomError(ERROR_NOT_ADMIN)));
        return reply;
    }

    private TLObject configureRoom(long channelId, String field, String value, TLObject success) {
        Conversation room = room(channelId);
        Account account = account();
        if (room == null || account == null) {
            return null;
        }
        DeferredReply reply = new DeferredReply();
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(field, value);
        whenDone(account.getXmppConnection().getManager(MultiUserChatManager.class).pushConfiguration(room, configuration),
                done -> {
                    if (success != null) {
                        reply.complete(success, null);
                    } else {
                        finishWithRoom(reply, channelId);
                    }
                },
                () -> reply.complete(null, roomError(ERROR_NOT_ADMIN)));
        return reply;
    }

    @Override
    public synchronized TLObject renameRoom(long channelId, String title) {
        return configureRoom(channelId, "muc#roomconfig_roomname", title, null);
    }

    @Override
    public synchronized TLObject describeRoom(long channelId, String about) {
        return configureRoom(channelId, "muc#roomconfig_roomdesc", about, new TLRPC.TL_boolTrue());
    }

    @Override
    public synchronized TLObject setRoomPhoto(long channelId, File image) {
        Conversation room = room(channelId);
        if (room == null) {
            image.delete();
            return null;
        }
        DeferredReply reply = new DeferredReply();
        service.publishMucAvatar(room, Uri.fromFile(image), new OnAvatarPublication() {
            @Override
            public void onAvatarPublicationSucceeded() {
                image.delete();
                finishWithRoom(reply, channelId);
            }

            @Override
            public void onAvatarPublicationFailed(int res) {
                image.delete();
                reply.complete(null, roomError(ERROR_NOT_ADMIN));
            }
        });
        return reply;
    }

    /**
     * XMPP has no forwarding, so a forwarded message goes out as a copy. Telegram still shows it
     * with the forward header, and a file keeps pointing at the original's copy on this device.
     */
    @Override
    public synchronized TLRPC.Updates forward(List<Integer> messageIds, List<Long> randomIds, long toUserId) {
        Conversation conversation = conversation(toUserId);
        if (conversation == null) {
            return null;
        }
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        for (int a = 0; a < messageIds.size() && a < randomIds.size(); a++) {
            Message original = engineMessage(messageIds.get(a));
            if (original == null) {
                continue;
            }
            TLRPC.Message originalTl = toTl(original, peerOf(original));
            Message copy;
            if (isMedia(original)) {
                File source = service.getFileBackend().getFile(original);
                if (!source.exists()) {
                    continue;
                }
                File upload = new File(context.getCacheDir(), "forward_" + original.getUuid());
                try {
                    copyFile(source, upload);
                } catch (Exception e) {
                    FileLog.e(e);
                    continue;
                }
                Message file = new Message(conversation, "", Message.ENCRYPTION_NONE);
                file.setCounterpart(conversation.getNextCounterpart());
                file.setType(original.getType() == Message.TYPE_IMAGE ? Message.TYPE_IMAGE : Message.TYPE_FILE);
                ids.messageId(file.getUuid(), file.getTimeSent());
                String mime = original.getMimeType();
                whenEncryptionDecided(conversation, file, upload::delete,
                        () -> service.attachFile(file, Uri.fromFile(upload), mime, upload::delete, () -> FileLog.e("cogram: could not forward a file")));
                copy = file;
            } else {
                String text = shownText(original);
                if (text == null || TEXT_UNREADABLE.equals(textOf(original))) {
                    continue;
                }
                Message message = new Message(conversation, text, Message.ENCRYPTION_NONE);
                ids.messageId(message.getUuid(), message.getTimeSent());
                whenEncryptionDecided(conversation, message, () -> FileLog.e("cogram: could not encrypt a forwarded message"),
                        () -> service.sendMessage(message));
                copy = message;
            }
            int id = ids.messageId(copy.getUuid(), copy.getTimeSent());
            TLRPC.TL_message tl = new TLRPC.TL_message();
            tl.id = id;
            tl.out = true;
            tl.date = (int) (copy.getTimeSent() / 1000);
            tl.message = originalTl.message;
            tl.media = originalTl.media;
            boolean room = isRoom(conversation);
            tl.peer_id = peerOf(toUserId, room);
            tl.from_id = peerUser(selfId);
            tl.dialog_id = room ? -toUserId : toUserId;
            TLRPC.TL_messageFwdHeader header = new TLRPC.TL_messageFwdHeader();
            header.from_id = originalTl.from_id;
            header.date = originalTl.date;
            header.flags |= TLObject.FLAG_0;
            tl.fwd_from = header;
            tl.flags = TLObject.FLAG_8 | TLObject.FLAG_9 | TLObject.FLAG_2;
            snapshots.put(id, snapshot(copy));
            if (!(tl.media instanceof TLRPC.TL_messageMediaEmpty)) {
                storeSentMedia(id, tl.media);
            }
            TL_update.TL_updateMessageID messageId = new TL_update.TL_updateMessageID();
            messageId.id = id;
            messageId.random_id = randomIds.get(a);
            if (room) {
                TLRPC.TL_updates channelUpdates = channelMessageUpdate(tl);
                updates.updates.add(messageId);
                updates.updates.addAll(channelUpdates.updates);
                if (updates.chats.isEmpty()) {
                    updates.chats.addAll(channelUpdates.chats);
                }
                continue;
            }
            pts++;
            savePts();
            ids.setMessagePts(id, pts);
            TL_update.TL_updateNewMessage newMessage = new TL_update.TL_updateNewMessage();
            newMessage.message = tl;
            newMessage.pts = pts;
            newMessage.pts_count = 1;
            updates.updates.add(messageId);
            updates.updates.add(newMessage);
        }
        updates.date = now();
        return updates;
    }

    private static void copyFile(File source, File target) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(source); java.io.OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    /** Returns when the contact read the message, or 0 when it happened while the app was closed. */
    @Override
    public synchronized int readDate(long userId, int id) {
        return ids.readDate(userId, id);
    }

    /** Telegram fetches replied messages it has not loaded, to show them in reply headers. */
    @Override
    public synchronized List<TLRPC.Message> messages(List<Integer> messageIds) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        for (int id : messageIds) {
            Message message = engineMessage(id);
            if (message != null && message.getConversation() instanceof Conversation) {
                result.add(toTl(message, peerOf(message)));
            }
        }
        return result;
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
    public synchronized List<TLRPC.Message> messagesAfter(int afterPts) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        Set<String> uuids = ids.messagesAfterPts(afterPts);
        for (Conversation conversation : chats()) {
            for (Message message : loadedMessages(conversation)) {
                if (uuids.contains(message.getUuid())) {
                    result.add(toTl(message, peerId(conversation)));
                }
            }
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("cogram: difference after pts " + afterPts + " current = " + pts + " messages = " + result.size());
        }
        return result;
    }

    @Override
    public synchronized int pts() {
        return pts;
    }

    @Override
    public synchronized void signOut() {
        listener = null;
        if (service != null && calls != null) {
            service.removeRtpConnectionUpdateListener(calls);
            calls = null;
        }
        if (service != null) {
            for (Account account : new ArrayList<>(service.getAccounts())) {
                service.deleteAccount(account);
            }
            context.unbindService(connection);
            service = null;
        }
        preferences.edit().clear().apply();
        ids.erase(context);
    }
}
