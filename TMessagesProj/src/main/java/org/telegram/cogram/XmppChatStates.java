package org.telegram.cogram;

import android.content.Context;
import android.os.SystemClock;

import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.manager.ChatStateManager;
import im.conversations.android.xmpp.model.hints.NoStore;
import im.conversations.android.xmpp.model.stanza.Message;
import im.conversations.android.xmpp.model.state.Active;
import im.conversations.android.xmpp.model.state.ChatStateNotification;
import im.conversations.android.xmpp.model.state.Composing;
import im.conversations.android.xmpp.model.state.Paused;

/**
 * Telegram's "typing", "recording a voice message" and "sending a file" both ways in one-to-one chats, over XMPP's
 * chat states (XEP-0085). XMPP has only composing, so what is being done goes with it in an element of Phogram's own,
 * which other apps leave aside and show as typing.
 */
final class XmppChatStates implements ChatStateManager.IncomingListener {

    interface Peers {
        /** The one-to-one chat with the user, or null. */
        Conversation chat(long userId);

        /** The user of a one-to-one chat with the address, or 0. */
        long userId(Account account, Jid address);

        void push(TLRPC.Updates updates, long userId);
    }

    private static final String NAMESPACE = "urn:phogram:chat-action:0";
    private static final String ELEMENT = "action";
    // Telegram repeats a state about every 5 seconds while it lasts and drops it after 6 seconds without one.
    private static final long REPEAT_MS = 4_000;
    // What is sent goes again now and then, so a long recording is not taken for over; stopping is told after a pause.
    private static final long RESEND_MS = 20_000;
    private static final long IDLE_MS = 8_000;
    // A state heard and never ended, as when the other phone loses its connection, ends by itself.
    private static final long HEARD_MS = 30_000;

    private final AppSettings settings;
    private final Peers peers;
    // Everything runs on this one thread, so the maps need no lock.
    private final ScheduledExecutorService queue = Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, Sent> sent = new HashMap<>();
    private final Map<Long, Heard> heard = new HashMap<>();
    private boolean repeating;

    private static final class Sent {
        final String kind;
        final long sentAt;
        long touchedAt;

        Sent(String kind, long now) {
            this.kind = kind;
            this.sentAt = now;
            this.touchedAt = now;
        }
    }

    private static final class Heard {
        final TLRPC.SendMessageAction action;
        final long at;

        Heard(TLRPC.SendMessageAction action, long at) {
            this.action = action;
            this.at = at;
        }
    }

    XmppChatStates(Context context, Peers peers) {
        this.settings = new AppSettings(context);
        this.peers = peers;
    }

    // What the user does, to the other side.

    void send(long userId, TLRPC.SendMessageAction action) {
        queue.execute(() -> {
            try {
                sendNow(userId, kindOf(action));
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** {@code kind} is null when nothing is being done any more, empty for typing. */
    private void sendNow(long userId, String kind) {
        if (!settings.isSendChatStates()) return;
        final Conversation chat = peers.chat(userId);
        if (chat == null) return;
        final long now = SystemClock.elapsedRealtime();
        // The engine's own state too, which a message sent puts back to active.
        final boolean changed = manager(chat).setOutgoingChatState(chat, kind == null ? Active.class : Composing.class);
        final Sent last = sent.get(userId);
        if (last != null && kind != null) last.touchedAt = now;
        final boolean same = last == null ? kind == null : Objects.equals(last.kind, kind);
        if (!changed && same && (kind == null || now - last.sentAt < RESEND_MS)) return;
        if (kind == null) {
            sent.remove(userId);
        } else {
            sent.put(userId, new Sent(kind, now));
            queue.schedule(() -> stopIfIdle(userId), IDLE_MS, TimeUnit.MILLISECONDS);
        }
        packet(chat, kind == null ? new Active() : new Composing(), kind);
    }

    /** Telegram never says typing stopped; XMPP waits to hear it. */
    private void stopIfIdle(long userId) {
        final Sent last = sent.get(userId);
        if (last == null || SystemClock.elapsedRealtime() - last.touchedAt < IDLE_MS) return;
        sent.remove(userId);
        final Conversation chat = peers.chat(userId);
        if (chat != null && manager(chat).setOutgoingChatState(chat, Paused.class)) {
            packet(chat, new Paused(), null);
        }
    }

    private static void packet(Conversation chat, ChatStateNotification state, String kind) {
        final Message packet = new Message(Message.Type.CHAT);
        packet.setTo(chat.getAddress().asBareJid());
        packet.addExtension(state);
        if (kind != null && !kind.isEmpty()) {
            packet.addChild(ELEMENT, NAMESPACE).setAttribute("type", kind);
        }
        packet.addExtension(new NoStore());
        chat.getAccount().getXmppConnection().sendMessagePacket(packet);
    }

    private static ChatStateManager manager(Conversation chat) {
        return chat.getAccount().getXmppConnection().getManager(ChatStateManager.class);
    }

    // What the other side does, to Telegram.

    @Override
    public void onChatState(Account account, Jid from, Class<? extends ChatStateNotification> state, Message message) {
        final Element action = message.findChild(ELEMENT, NAMESPACE);
        final String kind = action == null ? "" : action.getAttribute("type");
        queue.execute(() -> {
            try {
                heard(peers.userId(account, from), state == Composing.class ? actionOf(kind) : null);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private void heard(long userId, TLRPC.SendMessageAction action) {
        if (userId == 0) return;
        if (action == null) {
            if (heard.remove(userId) != null) push(userId, new TLRPC.TL_sendMessageCancelAction());
            return;
        }
        heard.put(userId, new Heard(action, SystemClock.elapsedRealtime()));
        push(userId, action);
        if (!repeating) {
            repeating = true;
            queue.schedule(this::repeat, REPEAT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void repeat() {
        final long now = SystemClock.elapsedRealtime();
        for (Map.Entry<Long, Heard> entry : new ArrayList<>(heard.entrySet())) {
            if (now - entry.getValue().at >= HEARD_MS) {
                heard.remove(entry.getKey());
                push(entry.getKey(), new TLRPC.TL_sendMessageCancelAction());
            } else {
                push(entry.getKey(), entry.getValue().action);
            }
        }
        repeating = !heard.isEmpty();
        if (repeating) queue.schedule(this::repeat, REPEAT_MS, TimeUnit.MILLISECONDS);
    }

    private void push(long userId, TLRPC.SendMessageAction action) {
        final TL_update.TL_updateUserTyping typing = new TL_update.TL_updateUserTyping();
        typing.user_id = userId;
        typing.action = action;
        final TLRPC.TL_updateShort update = new TLRPC.TL_updateShort();
        update.update = typing;
        update.date = (int) (System.currentTimeMillis() / 1000);
        peers.push(update, userId);
    }

    // Telegram's actions and their names on the wire.

    private static String kindOf(TLRPC.SendMessageAction action) {
        if (action instanceof TLRPC.TL_sendMessageCancelAction) return null;
        if (action instanceof TLRPC.TL_sendMessageRecordAudioAction) return "record-voice";
        if (action instanceof TLRPC.TL_sendMessageUploadAudioAction) return "upload-voice";
        if (action instanceof TLRPC.TL_sendMessageRecordRoundAction) return "record-round";
        if (action instanceof TLRPC.TL_sendMessageUploadRoundAction) return "upload-round";
        if (action instanceof TLRPC.TL_sendMessageRecordVideoAction) return "record-video";
        if (action instanceof TLRPC.TL_sendMessageUploadVideoAction) return "upload-video";
        if (action instanceof TLRPC.TL_sendMessageUploadPhotoAction) return "upload-photo";
        if (action instanceof TLRPC.TL_sendMessageUploadDocumentAction) return "upload-file";
        return "";
    }

    private static TLRPC.SendMessageAction actionOf(String kind) {
        if (kind == null) kind = "";
        switch (kind) {
            case "record-voice": return new TLRPC.TL_sendMessageRecordAudioAction();
            case "upload-voice": return new TLRPC.TL_sendMessageUploadAudioAction();
            case "record-round": return new TLRPC.TL_sendMessageRecordRoundAction();
            case "upload-round": return new TLRPC.TL_sendMessageUploadRoundAction();
            case "record-video": return new TLRPC.TL_sendMessageRecordVideoAction();
            case "upload-video": return new TLRPC.TL_sendMessageUploadVideoAction();
            case "upload-photo": return new TLRPC.TL_sendMessageUploadPhotoAction();
            case "upload-file": return new TLRPC.TL_sendMessageUploadDocumentAction();
            default: return new TLRPC.TL_sendMessageTypingAction();
        }
    }
}
