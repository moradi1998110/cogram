package org.telegram.cogram;

import org.telegram.tgnet.TLRPC;

import java.util.List;

/**
 * Source of chat data behind {@link CogramGateway}. The demo backend proves the Telegram UI runs
 * without its network layer; an XMPP backend replaces it later.
 */
public interface GatewayBackend {

    interface UpdatesListener {
        void onUpdates(TLRPC.Updates updates);
    }

    void setUpdatesListener(UpdatesListener listener);

    TLRPC.User selfUser();

    List<TLRPC.User> users();

    List<TLRPC.Dialog> dialogs();

    List<TLRPC.Message> topMessages();

    /** Returns messages of a chat, newest first. A zero offsetId starts from the newest message. */
    List<TLRPC.Message> history(long userId, int offsetId, int limit, int maxId, int minId);

    TLRPC.TL_updateShortSentMessage sendText(long userId, String text);

    TLRPC.TL_messages_affectedMessages markRead(long userId, int maxId);

    int pts();
}
