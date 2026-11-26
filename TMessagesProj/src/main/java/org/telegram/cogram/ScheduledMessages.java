package org.telegram.cogram;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Telegram's scheduled messages, kept on the phone since XMPP servers have no such thing. When one is
 * due it leaves the scheduled list and Telegram sends it as if it were typed then, so it takes the
 * same way as every other message, encryption checks included. Only text can be scheduled: a file
 * would have to be kept here until it goes. "Send when online" waits for the contact's presence.
 * The text stays in the app's private storage and reaches no server before it is sent. A phone that
 * is off at the time sends it once it is back.
 */
// Public, as Android creates its alarm receiver by name.
public final class ScheduledMessages {

    // Telegram's date for "send when online".
    static final int WHEN_ONLINE = 0x7FFFFFFE;
    private static final String PREFERENCES = "cogram_scheduled";

    private static final class Entry {
        final int id;
        final long dialogId;
        String text;
        final int replyTo;
        int date;
        int repeat;

        Entry(int id, long dialogId, String text, int replyTo, int date, int repeat) {
            this.id = id;
            this.dialogId = dialogId;
            this.text = text;
            this.replyTo = replyTo;
            this.date = date;
            this.repeat = repeat;
        }

        boolean dueAt(int now) {
            return date != WHEN_ONLINE && date <= now;
        }
    }

    private final int account;
    private final SharedPreferences preferences;
    private final Map<Integer, Entry> entries = new TreeMap<>();

    ScheduledMessages(int account) {
        this.account = account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE);
        for (Map.Entry<String, ?> stored : preferences.getAll().entrySet()) {
            try {
                JSONObject json = new JSONObject(String.valueOf(stored.getValue()));
                Entry entry = new Entry(json.getInt("id"), json.getLong("dialog"), json.getString("text"),
                        json.optInt("reply"), json.getInt("date"), json.optInt("repeat"));
                entries.put(entry.id, entry);
            } catch (JSONException e) {
                FileLog.e(e);
            }
        }
        // Android forgets alarms on a restart, and this runs when the app starts again.
        arm();
    }

    /** A scheduled message's request, answered here, or null when it is not one. */
    synchronized TLObject handle(TLObject request, GatewayBackend backend) {
        if (request instanceof TLRPC.TL_messages_sendMessage && ((TLRPC.TL_messages_sendMessage) request).schedule_date != 0) {
            TLRPC.TL_messages_sendMessage send = (TLRPC.TL_messages_sendMessage) request;
            int replyTo = send.reply_to instanceof TLRPC.TL_inputReplyToMessage ? ((TLRPC.TL_inputReplyToMessage) send.reply_to).reply_to_msg_id : 0;
            int id = backend.reserveMessageId("scheduled:" + UUID.randomUUID(), System.currentTimeMillis());
            Entry entry = new Entry(id, DialogObject.getPeerDialogId(send.peer), send.message, replyTo, send.schedule_date, send.schedule_repeat_period);
            save(entry);
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            TL_update.TL_updateMessageID messageId = new TL_update.TL_updateMessageID();
            messageId.id = id;
            messageId.random_id = send.random_id;
            updates.updates.add(messageId);
            updates.updates.add(newScheduled(entry, backend.selfUser().id));
            return updates;
        } else if (request instanceof TLRPC.TL_messages_editMessage && ((TLRPC.TL_messages_editMessage) request).schedule_date != 0) {
            TLRPC.TL_messages_editMessage edit = (TLRPC.TL_messages_editMessage) request;
            Entry entry = entries.get(edit.id);
            if (entry == null || edit.media != null) {
                return null;
            }
            if (edit.message != null) {
                entry.text = edit.message;
            }
            entry.date = edit.schedule_date;
            entry.repeat = edit.schedule_repeat_period;
            save(entry);
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.updates.add(newScheduled(entry, backend.selfUser().id));
            return updates;
        } else if (request instanceof TLRPC.TL_messages_getScheduledHistory) {
            return messages(DialogObject.getPeerDialogId(((TLRPC.TL_messages_getScheduledHistory) request).peer), null, backend);
        } else if (request instanceof TLRPC.TL_messages_getScheduledMessages) {
            TLRPC.TL_messages_getScheduledMessages get = (TLRPC.TL_messages_getScheduledMessages) request;
            return messages(DialogObject.getPeerDialogId(get.peer), get.id, backend);
        } else if (request instanceof TLRPC.TL_messages_deleteScheduledMessages) {
            TLRPC.TL_messages_deleteScheduledMessages delete = (TLRPC.TL_messages_deleteScheduledMessages) request;
            return removed(take(DialogObject.getPeerDialogId(delete.peer), delete.id));
        } else if (request instanceof TLRPC.TL_messages_sendScheduledMessages) {
            TLRPC.TL_messages_sendScheduledMessages now = (TLRPC.TL_messages_sendScheduledMessages) request;
            List<Entry> taken = take(DialogObject.getPeerDialogId(now.peer), now.id);
            for (Entry entry : taken) {
                send(entry);
            }
            return removed(taken);
        }
        return null;
    }

    /** Sends what is due, and what waits for a contact who is now online. Called every second. */
    void tick(GatewayBackend backend) {
        ArrayList<TLRPC.Updates> updates = new ArrayList<>();
        ArrayList<Entry> due = new ArrayList<>();
        synchronized (this) {
            int now = (int) (System.currentTimeMillis() / 1000);
            for (Iterator<Entry> iterator = entries.values().iterator(); iterator.hasNext(); ) {
                Entry entry = iterator.next();
                if (!entry.dueAt(now) && !(entry.date == WHEN_ONLINE && isOnline(backend, entry.dialogId))) {
                    continue;
                }
                due.add(new Entry(entry.id, entry.dialogId, entry.text, entry.replyTo, entry.date, 0));
                if (entry.repeat > 0 && entry.date != WHEN_ONLINE) {
                    // A repeating message stays, at its next time after now.
                    while (entry.date <= now) {
                        entry.date += entry.repeat;
                    }
                    save(entry);
                    TLRPC.TL_updates next = new TLRPC.TL_updates();
                    next.updates.add(newScheduled(entry, backend.selfUser().id));
                    updates.add(next);
                } else {
                    iterator.remove();
                    preferences.edit().remove(key(entry.id)).apply();
                    ArrayList<Entry> one = new ArrayList<>();
                    one.add(entry);
                    updates.add(removed(one));
                }
            }
        }
        if (due.isEmpty()) {
            return;
        }
        for (TLRPC.Updates update : updates) {
            push(update);
        }
        for (Entry entry : due) {
            send(entry);
        }
        arm();
    }

    private static boolean isOnline(GatewayBackend backend, long dialogId) {
        TLRPC.User user = dialogId > 0 ? backend.user(dialogId) : null;
        return user != null && user.status instanceof TLRPC.TL_userStatusOnline;
    }

    private List<Entry> take(long dialogId, List<Integer> ids) {
        ArrayList<Entry> taken = new ArrayList<>();
        for (int id : ids) {
            Entry entry = entries.get(id);
            if (entry != null && entry.dialogId == dialogId) {
                entries.remove(id);
                preferences.edit().remove(key(id)).apply();
                taken.add(entry);
            }
        }
        arm();
        return taken;
    }

    private void save(Entry entry) {
        entries.put(entry.id, entry);
        try {
            JSONObject json = new JSONObject();
            json.put("id", entry.id);
            json.put("dialog", entry.dialogId);
            json.put("text", entry.text);
            json.put("reply", entry.replyTo);
            json.put("date", entry.date);
            json.put("repeat", entry.repeat);
            preferences.edit().putString(key(entry.id), json.toString()).apply();
        } catch (JSONException e) {
            FileLog.e(e);
        }
        arm();
    }

    private static String key(int id) {
        return "m_" + id;
    }

    private TLRPC.TL_messages_messages messages(long dialogId, List<Integer> only, GatewayBackend backend) {
        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        long selfId = backend.selfUser().id;
        for (Entry entry : entries.values()) {
            if (entry.dialogId == dialogId && (only == null || only.contains(entry.id))) {
                result.messages.add(message(entry, selfId));
            }
        }
        result.users.addAll(backend.users());
        return result;
    }

    private static TLRPC.Updates removed(List<Entry> taken) {
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        for (Entry entry : taken) {
            TL_update.TL_updateDeleteScheduledMessages deleted = new TL_update.TL_updateDeleteScheduledMessages();
            deleted.peer = peer(entry.dialogId);
            deleted.messages.add(entry.id);
            updates.updates.add(deleted);
        }
        return updates;
    }

    private static TL_update.TL_updateNewScheduledMessage newScheduled(Entry entry, long selfId) {
        TL_update.TL_updateNewScheduledMessage update = new TL_update.TL_updateNewScheduledMessage();
        update.message = message(entry, selfId);
        return update;
    }

    private static TLRPC.Message message(Entry entry, long selfId) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = entry.id;
        message.out = true;
        message.date = entry.date;
        message.message = entry.text;
        message.peer_id = peer(entry.dialogId);
        message.dialog_id = entry.dialogId;
        TLRPC.TL_peerUser self = new TLRPC.TL_peerUser();
        self.user_id = selfId;
        message.from_id = self;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.flags = TLObject.FLAG_8 | TLObject.FLAG_9;
        if (entry.replyTo != 0) {
            TLRPC.TL_messageReplyHeader header = new TLRPC.TL_messageReplyHeader();
            header.reply_to_msg_id = entry.replyTo;
            header.flags |= TLObject.FLAG_4;
            message.reply_to = header;
            message.flags |= TLObject.FLAG_3;
        }
        message.schedule_repeat_period = entry.repeat;
        return message;
    }

    private static TLRPC.Peer peer(long dialogId) {
        if (dialogId > 0) {
            TLRPC.TL_peerUser user = new TLRPC.TL_peerUser();
            user.user_id = dialogId;
            return user;
        }
        TLRPC.TL_peerChannel channel = new TLRPC.TL_peerChannel();
        channel.channel_id = -dialogId;
        return channel;
    }

    private void push(TLRPC.Updates updates) {
        Utilities.stageQueue.postRunnable(() -> AccountInstance.getInstance(account).getMessagesController().processUpdates(updates, false));
    }

    /** Sends it the way a reply from a notification is sent, once Telegram knows its chat. */
    private void send(Entry entry) {
        AndroidUtilities.runOnUIThread(() -> {
            AccountInstance instance = AccountInstance.getInstance(account);
            MessagesController controller = instance.getMessagesController();
            boolean known = entry.dialogId > 0 ? controller.getUser(entry.dialogId) != null : controller.getChat(-entry.dialogId) != null;
            if (known) {
                sendKnown(instance, entry);
                return;
            }
            Utilities.globalQueue.postRunnable(() -> {
                TLRPC.User user = entry.dialogId > 0 ? instance.getMessagesStorage().getUserSync(entry.dialogId) : null;
                TLRPC.Chat chat = entry.dialogId < 0 ? instance.getMessagesStorage().getChatSync(-entry.dialogId) : null;
                AndroidUtilities.runOnUIThread(() -> {
                    if (user != null) {
                        controller.putUser(user, true);
                    }
                    if (chat != null) {
                        controller.putChat(chat, true);
                    }
                    sendKnown(instance, entry);
                });
            });
        });
    }

    private static void sendKnown(AccountInstance instance, Entry entry) {
        MessageObject replyTo = null;
        if (entry.replyTo != 0) {
            TLRPC.TL_message replied = new TLRPC.TL_message();
            replied.message = "";
            replied.id = entry.replyTo;
            replied.peer_id = instance.getMessagesController().getPeer(entry.dialogId);
            replyTo = new MessageObject(instance.getCurrentAccount(), replied, false, false);
        }
        instance.getSendMessagesHelper().sendMessage(SendMessagesHelper.SendMessageParams.of(entry.text, entry.dialogId,
                replyTo, null, null, true, null, null, null, true, 0, 0, null, false));
    }

    /**
     * Wakes the app for the next timed message, in case it is closed then; while it runs, the
     * gateway's tick sends on time. Exact where Android allows it, else within Android's window.
     */
    private synchronized void arm() {
        Context context = ApplicationLoader.applicationContext;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) {
            return;
        }
        PendingIntent intent = PendingIntent.getBroadcast(context, 0, new Intent(context, Alarm.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long next = Long.MAX_VALUE;
        for (Entry entry : entries.values()) {
            if (entry.date != WHEN_ONLINE) {
                next = Math.min(next, entry.date * 1000L);
            }
        }
        if (next == Long.MAX_VALUE) {
            alarms.cancel(intent);
        } else if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent);
        }
    }

    /** Started by the alarm, and after a restart, when Android has forgotten the alarms. */
    public static final class Alarm extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            ApplicationLoader.postInitApplication();
            CogramGateway.getInstance(0).tickScheduled();
        }
    }
}
