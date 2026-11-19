package org.telegram.cogram;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.tgnet.tl.TL_forum;
import org.telegram.tgnet.tl.TL_update;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Phogram's AI assistant, shown to Telegram as a bot with the AI chat view Telegram gives bots with topics: each
 * conversation is a topic of the bot's chat, a reply appears as it is written (Telegram's text drafts), and the
 * send button turns into a stop button meanwhile.
 *
 * The gateway hands it every request about the assistant's chat. Conversations are kept in a file on the phone.
 * The replies come from an {@link AssistantReplySource}: a sample for now, the organization's AI server next.
 */
final class AssistantBot {

    /** The assistant's user id: far above the ids the engine gives people, so it never meets one. */
    static final long USER_ID = 7_000_000_001L;

    interface Host {
        long selfId();

        int pts();

        int nextPts();

        int reserveMessageId(String key, long timeMillis);

        void push(TLRPC.Updates updates);
    }

    private static final String FILE = "assistant.json";

    private final Host host;
    private final File file;
    private final AssistantReplySource replies;

    /** A conversation; Telegram knows it by the id of the service message that opened it. */
    private static final class Topic {
        int id;
        int date;
        String title;
        boolean titleMissing;
    }

    private static final class Entry {
        int id;
        int topic;
        int date;
        boolean out;
        boolean service;
        String text;
    }

    private final ArrayList<Topic> topics = new ArrayList<>();
    private final ArrayList<Entry> entries = new ArrayList<>();

    /**
     * The reply being written; at most one, as Telegram's stop button stops one. It hears the reply source and
     * ignores whatever arrives after it stopped being the current reply.
     */
    private final class Stream implements AssistantReplySource.Listener {
        final int topic;
        final long randomId = Utilities.random.nextLong();
        final StringBuilder text = new StringBuilder();
        AssistantReplySource.Call call;

        Stream(int topic) {
            this.topic = topic;
        }

        @Override
        public void onText(String more) {
            String partial;
            synchronized (AssistantBot.this) {
                if (stream != this) return;
                text.append(more);
                partial = text.toString();
            }
            pushDraft(this, partial);
        }

        @Override
        public void onDone() {
            String reply;
            synchronized (AssistantBot.this) {
                if (stream != this) return;
                reply = text.toString();
            }
            finish(this, reply);
        }

        @Override
        public void onError(String message) {
            FileLog.e("cogram: the assistant's reply failed: " + message);
            String reply;
            synchronized (AssistantBot.this) {
                if (stream != this) return;
                reply = text.toString();
            }
            String failed = LocaleController.getString(R.string.PhogramAssistantFailed);
            finish(this, reply.isEmpty() ? failed : reply + "…\n\n" + failed);
        }
    }

    private Stream stream;

    AssistantBot(Host host, int account) {
        this.host = host;
        this.file = new File(ApplicationLoader.getFilesDirFixed(), account == 0 ? FILE : account + "_" + FILE);
        this.replies = AssistantReplySource.forAccount(account);
        load();
    }

    static boolean isAssistant(long userId) {
        return userId == USER_ID;
    }

    static boolean isAssistant(TLRPC.InputPeer peer) {
        return peer instanceof TLRPC.TL_inputPeerUser && peer.user_id == USER_ID;
    }

    /* The assistant as a Telegram user and bot. */

    TLRPC.User user() {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = USER_ID;
        user.first_name = LocaleController.getString(R.string.PhogramAssistantTab);
        user.bot = true;
        user.bot_nochats = true;
        user.bot_forum_view = true;
        user.bot_forum_can_manage_topics = true;
        user.bot_info_version = 1;
        user.flags |= TLObject.FLAG_1 | TLObject.FLAG_14;
        user.flags2 |= TLObject.FLAG_16;
        return user;
    }

    TLRPC.UserFull fullUser() {
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = USER_ID;
        full.settings = new TLRPC.TL_peerSettings();
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        TL_bots.TL_botInfo info = new TL_bots.TL_botInfo();
        info.user_id = USER_ID;
        info.description = LocaleController.getString(R.string.PhogramAssistantIntro);
        info.flags |= TLObject.FLAG_0 | TLObject.FLAG_1;
        full.bot_info = info;
        full.flags |= TLObject.FLAG_3;
        return full;
    }

    /** The assistant's chat as a dialog, with its newest message, for Telegram's chat screen. */
    synchronized TLRPC.TL_dialog dialog(List<TLRPC.Message> topMessages) {
        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
        dialog.id = USER_ID;
        dialog.peer = peerUser(USER_ID);
        dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
        Entry newest = null;
        for (Entry entry : entries) {
            if (newest == null || entry.id > newest.id) newest = entry;
        }
        if (newest != null) {
            dialog.top_message = newest.id;
            dialog.read_inbox_max_id = newest.id;
            dialog.read_outbox_max_id = newest.id;
            topMessages.add(toMessage(newest));
        }
        return dialog;
    }

    /** The topics Phogram's assistant page lists, newest first: title and last activity. */
    synchronized List<TLRPC.TL_forumTopic> recentTopics() {
        ArrayList<TLRPC.TL_forumTopic> result = new ArrayList<>();
        for (Topic topic : topics) {
            result.add(forumTopic(topic));
        }
        Collections.sort(result, (a, b) -> Integer.compare(b.top_message, a.top_message));
        return result;
    }

    /**
     * Answers a request about the assistant's chat, or returns null when the request is about something else.
     */
    TLObject handle(TLObject request) {
        if (request instanceof TL_forum.TL_messages_createForumTopic) {
            TL_forum.TL_messages_createForumTopic create = (TL_forum.TL_messages_createForumTopic) request;
            return isAssistant(create.peer) ? createTopic(create) : null;
        } else if (request instanceof TL_forum.TL_messages_getForumTopics) {
            TL_forum.TL_messages_getForumTopics get = (TL_forum.TL_messages_getForumTopics) request;
            return isAssistant(get.peer) ? topics(null) : null;
        } else if (request instanceof TL_forum.TL_messages_getForumTopicsByID) {
            TL_forum.TL_messages_getForumTopicsByID get = (TL_forum.TL_messages_getForumTopicsByID) request;
            return isAssistant(get.peer) ? topics(get.topics) : null;
        } else if (request instanceof TL_forum.TL_messages_editForumTopic) {
            TL_forum.TL_messages_editForumTopic edit = (TL_forum.TL_messages_editForumTopic) request;
            return isAssistant(edit.peer) ? editTopic(edit) : null;
        } else if (request instanceof TL_forum.TL_messages_deleteTopicHistory) {
            TL_forum.TL_messages_deleteTopicHistory delete = (TL_forum.TL_messages_deleteTopicHistory) request;
            return isAssistant(delete.peer) ? deleteTopic(delete.top_msg_id) : null;
        } else if (request instanceof TLRPC.TL_messages_getReplies) {
            TLRPC.TL_messages_getReplies get = (TLRPC.TL_messages_getReplies) request;
            return isAssistant(get.peer) ? messages(get.msg_id, get.offset_id, get.add_offset, get.limit) : null;
        } else if (request instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.TL_messages_getHistory get = (TLRPC.TL_messages_getHistory) request;
            return isAssistant(get.peer) ? messages(0, get.offset_id, get.add_offset, get.limit) : null;
        } else if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage send = (TLRPC.TL_messages_sendMessage) request;
            return isAssistant(send.peer) ? send(send) : null;
        } else if (request instanceof TLRPC.TL_messages_setTyping) {
            TLRPC.TL_messages_setTyping typing = (TLRPC.TL_messages_setTyping) request;
            if (!isAssistant(typing.peer)) return null;
            if (typing.action instanceof TLRPC.TL_sendMessageStopDraftAction) {
                stop(((TLRPC.TL_sendMessageStopDraftAction) typing.action).random_id);
            }
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_messages_readHistory) {
            TLRPC.TL_messages_readHistory read = (TLRPC.TL_messages_readHistory) request;
            if (!isAssistant(read.peer)) return null;
            TLRPC.TL_messages_affectedMessages affected = new TLRPC.TL_messages_affectedMessages();
            affected.pts = host.pts();
            affected.pts_count = 0;
            return affected;
        } else if (request instanceof TLRPC.TL_messages_readDiscussion) {
            return isAssistant(((TLRPC.TL_messages_readDiscussion) request).peer) ? new TLRPC.TL_boolTrue() : null;
        } else if (request instanceof TLRPC.TL_messages_deleteHistory) {
            TLRPC.TL_messages_deleteHistory delete = (TLRPC.TL_messages_deleteHistory) request;
            return isAssistant(delete.peer) ? deleteAll() : null;
        } else if (request instanceof TLRPC.TL_messages_deleteMessages) {
            return deleteMessages(((TLRPC.TL_messages_deleteMessages) request).id);
        }
        return null;
    }

    /* Topics. */

    private TLObject createTopic(TL_forum.TL_messages_createForumTopic request) {
        TLRPC.TL_messageService service;
        synchronized (this) {
            Topic topic = new Topic();
            topic.date = now();
            topic.id = host.reserveMessageId("assistant:topic:" + UUID.randomUUID(), System.currentTimeMillis());
            topic.title = request.title;
            topic.titleMissing = request.title_missing;
            topics.add(topic);
            Entry entry = new Entry();
            entry.id = topic.id;
            entry.topic = topic.id;
            entry.date = topic.date;
            entry.out = true;
            entry.service = true;
            entry.text = topic.title;
            entries.add(entry);
            save();
            service = (TLRPC.TL_messageService) toMessage(entry);
        }
        TL_update.TL_updateMessageID messageId = new TL_update.TL_updateMessageID();
        messageId.id = service.id;
        messageId.random_id = request.random_id;
        TL_update.TL_updateNewMessage newMessage = new TL_update.TL_updateNewMessage();
        newMessage.message = service;
        newMessage.pts = host.nextPts();
        newMessage.pts_count = 1;
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates.add(messageId);
        updates.updates.add(newMessage);
        updates.users.add(user());
        updates.date = now();
        return updates;
    }

    private synchronized TLObject topics(List<Integer> only) {
        TLRPC.TL_messages_forumTopics result = new TLRPC.TL_messages_forumTopics();
        for (Topic topic : topics) {
            if (only != null && !only.contains(topic.id)) continue;
            TLRPC.TL_forumTopic forumTopic = forumTopic(topic);
            result.topics.add(forumTopic);
            Entry last = lastEntry(topic.id);
            if (last != null) {
                result.messages.add(toMessage(last));
            }
        }
        Collections.sort(result.topics, (a, b) -> Integer.compare(b.top_message, a.top_message));
        result.count = result.topics.size();
        result.users.add(user());
        result.pts = host.pts();
        return result;
    }

    private TLRPC.TL_forumTopic forumTopic(Topic topic) {
        TLRPC.TL_forumTopic forumTopic = new TLRPC.TL_forumTopic();
        Entry last = lastEntry(topic.id);
        forumTopic.id = topic.id;
        forumTopic.date = topic.date;
        forumTopic.title = topic.title;
        forumTopic.title_missing = topic.titleMissing;
        forumTopic.my = true;
        forumTopic.top_message = last != null ? last.id : topic.id;
        forumTopic.read_inbox_max_id = forumTopic.top_message;
        forumTopic.read_outbox_max_id = forumTopic.top_message;
        forumTopic.from_id = peerUser(host.selfId());
        forumTopic.peer = peerUser(USER_ID);
        forumTopic.notify_settings = new TLRPC.TL_peerNotifySettings();
        forumTopic.flags |= TLObject.FLAG_1;
        return forumTopic;
    }

    private TLObject editTopic(TL_forum.TL_messages_editForumTopic request) {
        synchronized (this) {
            Topic topic = topic(request.topic_id);
            if (topic != null && request.title != null) {
                topic.title = request.title;
                topic.titleMissing = false;
                save();
            }
        }
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = now();
        return updates;
    }

    private TLObject deleteTopic(int topicId) {
        synchronized (this) {
            if (stream != null && stream.topic == topicId) {
                cancelStream();
            }
            topics.remove(topic(topicId));
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).topic == topicId) {
                    entries.remove(i);
                }
            }
            save();
        }
        return affectedHistory();
    }

    private TLObject deleteAll() {
        synchronized (this) {
            cancelStream();
            topics.clear();
            entries.clear();
            save();
        }
        return affectedHistory();
    }

    private TLObject deleteMessages(List<Integer> ids) {
        synchronized (this) {
            boolean ours = false;
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry entry = entries.get(i);
                if (ids.contains(entry.id)) {
                    ours = true;
                    if (!entry.service) {
                        entries.remove(i);
                    }
                }
            }
            if (!ours) return null;
            save();
        }
        TLRPC.TL_messages_affectedMessages affected = new TLRPC.TL_messages_affectedMessages();
        affected.pts = host.nextPts();
        affected.pts_count = 1;
        return affected;
    }

    private TLObject affectedHistory() {
        TLRPC.TL_messages_affectedHistory affected = new TLRPC.TL_messages_affectedHistory();
        affected.pts = host.nextPts();
        affected.pts_count = 1;
        affected.offset = 0;
        return affected;
    }

    /* Messages. */

    /** A topic's messages (or all of them for topic 0), newest first, from offset_id down as Telegram pages. */
    private synchronized TLObject messages(int topicId, int offsetId, int addOffset, int limit) {
        ArrayList<Entry> matching = new ArrayList<>();
        for (Entry entry : entries) {
            if (topicId == 0 || entry.topic == topicId) {
                matching.add(entry);
            }
        }
        Collections.sort(matching, (a, b) -> Integer.compare(b.id, a.id));
        int start = 0;
        if (offsetId != 0) {
            while (start < matching.size() && matching.get(start).id >= offsetId) {
                start++;
            }
        }
        start = Math.max(0, start + addOffset);
        int end = Math.min(matching.size(), start + (limit <= 0 ? 50 : limit));
        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        for (int i = start; i < end; i++) {
            result.messages.add(toMessage(matching.get(i)));
        }
        if (topicId == 0) {
            for (Topic topic : topics) {
                result.topics.add(forumTopic(topic));
            }
        }
        result.users.add(user());
        return result;
    }

    private TLObject send(TLRPC.TL_messages_sendMessage request) {
        int topicId = request.reply_to instanceof TLRPC.TL_inputReplyToMessage
                ? (((TLRPC.TL_inputReplyToMessage) request.reply_to).top_msg_id != 0
                        ? ((TLRPC.TL_inputReplyToMessage) request.reply_to).top_msg_id
                        : ((TLRPC.TL_inputReplyToMessage) request.reply_to).reply_to_msg_id)
                : 0;
        Entry entry = new Entry();
        synchronized (this) {
            if (topic(topicId) == null) {
                // Telegram makes the topic first; a message without one starts a conversation of its own.
                topicId = 0;
            }
            entry.id = host.reserveMessageId("assistant:message:" + UUID.randomUUID(), System.currentTimeMillis());
            entry.topic = topicId;
            entry.date = now();
            entry.out = true;
            entry.text = request.message;
            entries.add(entry);
            save();
        }
        TLRPC.TL_updateShortSentMessage sent = new TLRPC.TL_updateShortSentMessage();
        sent.flags = TLObject.FLAG_1;
        sent.out = true;
        sent.id = entry.id;
        sent.pts = host.nextPts();
        sent.pts_count = 1;
        sent.date = entry.date;
        if (topicId != 0) {
            startReply(topicId);
        }
        return sent;
    }

    /* The reply: the source writes it, Telegram shows it as text drafts, then it is sent as the bot's message. */

    private void startReply(int topicId) {
        Stream current;
        List<AssistantReplySource.Turn> conversation;
        synchronized (this) {
            cancelStream();
            current = new Stream(topicId);
            stream = current;
            conversation = conversation(topicId);
        }
        AssistantReplySource.Call call = replies.reply(topicId, conversation, current);
        synchronized (this) {
            if (stream == current) {
                current.call = call;
            } else {
                call.cancel();
            }
        }
    }

    /** A topic's messages, oldest first, without the service message that opened it. */
    private List<AssistantReplySource.Turn> conversation(int topicId) {
        ArrayList<Entry> matching = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.topic == topicId && !entry.service) {
                matching.add(entry);
            }
        }
        Collections.sort(matching, (a, b) -> Integer.compare(a.id, b.id));
        ArrayList<AssistantReplySource.Turn> turns = new ArrayList<>();
        for (Entry entry : matching) {
            turns.add(new AssistantReplySource.Turn(entry.out, entry.text));
        }
        return turns;
    }

    private void pushDraft(Stream current, String text) {
        TLRPC.TL_sendMessageTextDraftAction action = new TLRPC.TL_sendMessageTextDraftAction();
        action.random_id = current.randomId;
        action.text = new TLRPC.TL_textWithEntities();
        action.text.text = text;
        action.can_stop = true;
        TL_update.TL_updateUserTyping typing = new TL_update.TL_updateUserTyping();
        typing.user_id = USER_ID;
        typing.top_msg_id = current.topic;
        typing.flags |= TLObject.FLAG_0;
        typing.action = action;
        TLRPC.TL_updateShort update = new TLRPC.TL_updateShort();
        update.update = typing;
        update.date = now();
        host.push(update);
    }

    /** Stops the reply Telegram's stop button names; what was written so far stays as the reply. */
    private void stop(long randomId) {
        Stream current;
        String partial;
        synchronized (this) {
            current = stream;
            if (current == null || current.randomId != randomId) return;
            partial = current.text.toString();
        }
        finish(current, partial.isEmpty() ? "…" : partial + "…");
    }

    private void finish(Stream current, String text) {
        Entry entry = new Entry();
        synchronized (this) {
            if (stream != current) return;
            cancelStream();
            entry.id = host.reserveMessageId("assistant:reply:" + UUID.randomUUID(), System.currentTimeMillis());
            entry.topic = current.topic;
            entry.date = now();
            entry.out = false;
            entry.text = text;
            entries.add(entry);
            save();
        }
        TL_update.TL_updateNewMessage newMessage = new TL_update.TL_updateNewMessage();
        newMessage.message = toMessage(entry);
        newMessage.pts = host.nextPts();
        newMessage.pts_count = 1;
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates.add(newMessage);
        updates.users.add(user());
        updates.date = now();
        host.push(updates);
    }

    private void cancelStream() {
        if (stream != null && stream.call != null) {
            stream.call.cancel();
        }
        stream = null;
    }

    /* Building Telegram's objects. */

    private TLRPC.Message toMessage(Entry entry) {
        TLRPC.Message message;
        if (entry.service) {
            TLRPC.TL_messageService service = new TLRPC.TL_messageService();
            TLRPC.TL_messageActionTopicCreate action = new TLRPC.TL_messageActionTopicCreate();
            action.title = entry.text;
            service.action = action;
            message = service;
        } else {
            TLRPC.TL_message text = new TLRPC.TL_message();
            text.message = entry.text;
            text.media = new TLRPC.TL_messageMediaEmpty();
            message = text;
            if (entry.topic != 0) {
                TLRPC.TL_messageReplyHeader header = new TLRPC.TL_messageReplyHeader();
                header.forum_topic = true;
                header.reply_to_msg_id = entry.topic;
                header.flags |= TLObject.FLAG_3 | TLObject.FLAG_4;
                message.reply_to = header;
                message.flags |= TLObject.FLAG_3;
            }
        }
        message.id = entry.id;
        message.date = entry.date;
        message.out = entry.out;
        message.peer_id = peerUser(USER_ID);
        message.from_id = peerUser(entry.out ? host.selfId() : USER_ID);
        message.dialog_id = USER_ID;
        message.flags |= TLObject.FLAG_8;
        return message;
    }

    private static TLRPC.Peer peerUser(long userId) {
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = userId;
        return peer;
    }

    private Topic topic(int id) {
        for (Topic topic : topics) {
            if (topic.id == id) return topic;
        }
        return null;
    }

    private Entry lastEntry(int topicId) {
        Entry last = null;
        for (Entry entry : entries) {
            if (entry.topic == topicId && (last == null || entry.id > last.id)) {
                last = entry;
            }
        }
        return last;
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    /* The conversations file. */

    private synchronized void load() {
        if (!file.exists()) return;
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = in.read(bytes, read, bytes.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray topicArray = root.optJSONArray("topics");
            for (int i = 0; topicArray != null && i < topicArray.length(); i++) {
                JSONObject o = topicArray.getJSONObject(i);
                Topic topic = new Topic();
                topic.id = o.getInt("id");
                topic.date = o.optInt("date");
                topic.title = o.optString("title");
                topic.titleMissing = o.optBoolean("titleMissing");
                topics.add(topic);
            }
            JSONArray entryArray = root.optJSONArray("messages");
            for (int i = 0; entryArray != null && i < entryArray.length(); i++) {
                JSONObject o = entryArray.getJSONObject(i);
                Entry entry = new Entry();
                entry.id = o.getInt("id");
                entry.topic = o.optInt("topic");
                entry.date = o.optInt("date");
                entry.out = o.optBoolean("out");
                entry.service = o.optBoolean("service");
                entry.text = o.optString("text");
                entries.add(entry);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private synchronized void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray topicArray = new JSONArray();
            for (Topic topic : topics) {
                JSONObject o = new JSONObject();
                o.put("id", topic.id);
                o.put("date", topic.date);
                o.put("title", topic.title);
                o.put("titleMissing", topic.titleMissing);
                topicArray.put(o);
            }
            JSONArray entryArray = new JSONArray();
            for (Entry entry : entries) {
                JSONObject o = new JSONObject();
                o.put("id", entry.id);
                o.put("topic", entry.topic);
                o.put("date", entry.date);
                o.put("out", entry.out);
                o.put("service", entry.service);
                o.put("text", entry.text);
                entryArray.put(o);
            }
            root.put("topics", topicArray);
            root.put("messages", entryArray);
            File temp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) {
                FileLog.e("cogram: could not save the assistant's conversations");
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
