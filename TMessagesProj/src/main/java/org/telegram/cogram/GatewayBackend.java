package org.telegram.cogram;

import org.telegram.tgnet.TLRPC;

import java.util.Collections;
import java.util.List;

/**
 * Source of chat data behind {@link CogramGateway}. The demo backend proves the Telegram UI runs
 * without its network layer, and {@link XmppBackend} serves real chats.
 */
public interface GatewayBackend {

    interface UpdatesListener {
        void onUpdates(TLRPC.Updates updates);
    }

    /** Runs the task once the backend can answer requests. */
    void whenReady(Runnable runnable);

    void setUpdatesListener(UpdatesListener listener);

    TLRPC.User selfUser();

    List<TLRPC.User> users();

    List<TLRPC.Dialog> dialogs();

    List<TLRPC.Message> topMessages();

    /** Returns messages of a chat, newest first. A zero offsetId starts from the newest message. */
    List<TLRPC.Message> history(long userId, int offsetId, int addOffset, int limit, int maxId, int minId);

    TLRPC.TL_updateShortSentMessage sendText(long userId, String text);

    TLRPC.TL_messages_affectedMessages markRead(long userId, int maxId);

    int pts();

    /** Returns messages already sent as updates after the given pts, for an app that missed them. */
    default List<TLRPC.Message> messagesAfter(int pts) {
        return Collections.emptyList();
    }
}
