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

    /** Removes the account and everything kept for it, after the user logs out. */
    default void signOut() {
    }

    default TLRPC.User user(long userId) {
        for (TLRPC.User user : users()) {
            if (user.id == userId) {
                return user;
            }
        }
        return null;
    }

    /** Returns part of a profile photo's file, or null when the photo is unknown. */
    default TLRPC.TL_upload_file photoFile(long photoId, long offset, int limit) {
        return null;
    }

    default TLRPC.UserFull fullUser(long userId) {
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = userId;
        full.settings = new TLRPC.TL_peerSettings();
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        return full;
    }

    default List<TLRPC.Photo> userPhotos(long userId) {
        return Collections.emptyList();
    }

    default TLRPC.User updateName(String firstName, String lastName) {
        return selfUser();
    }

    /** Sets the account's profile photo, and returns it, or null when it could not be published. */
    default TLRPC.Photo updatePhoto(byte[] image) {
        return null;
    }

    /** Returns the engine's call handling, or null when the backend has no calls. */
    default XmppCalls calls() {
        return null;
    }
}
