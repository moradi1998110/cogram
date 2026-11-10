package org.telegram.cogram;

import org.telegram.tgnet.TLObject;
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

    /** Returns the response, which may be a {@link DeferredReply} that completes once the message is really sent. */
    TLObject sendText(long userId, String text, int replyToId, long randomId);

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

    /** Returns the user's full user@server address, or null for a user the backend does not know. */
    default String address(long userId) {
        return null;
    }

    default List<TLRPC.TL_contact> contacts() {
        return Collections.emptyList();
    }

    default List<TLRPC.User> search(String query) {
        return Collections.emptyList();
    }

    /** Returns the user with this username or address, or null when there is none. */
    default TLRPC.User resolve(String username) {
        return null;
    }

    default TLRPC.User addContact(long userId, String firstName, String lastName) {
        return null;
    }

    default void deleteContact(long userId) {
    }

    default void setBlocked(long userId, boolean blocked) {
    }

    default TLRPC.PeerSettings peerSettings(long userId) {
        return new TLRPC.TL_peerSettings();
    }

    /** Returns call entries across all chats, newest first, older than offsetId when it is not zero. */
    default List<TLRPC.Message> callHistory(int offsetId, int limit) {
        return Collections.emptyList();
    }

    default TLRPC.Updates editText(long userId, int id, String text) {
        return null;
    }

    default TLRPC.Updates react(long userId, int id, List<String> emojis) {
        return null;
    }

    default TLRPC.TL_messages_affectedMessages deleteMessages(List<Integer> ids, boolean revoke) {
        return null;
    }

    default TLRPC.Updates forward(List<Integer> ids, List<Long> randomIds, long toUserId) {
        return null;
    }

    /** Returns when the other side read a message of ours, or 0 when that is not known. */
    default int readDate(long userId, int id) {
        return 0;
    }

    default List<TLRPC.Message> messages(List<Integer> ids) {
        return Collections.emptyList();
    }

    /** Returns part of a file in a chat, or null when there is no such file. */
    default TLRPC.TL_upload_file mediaFile(long mediaId, long offset, int limit) {
        return null;
    }

    /** Sends a file Telegram uploaded, and returns the updates that tell Telegram it was sent. */
    default TLObject sendFile(long userId, java.io.File file, String mime, List<TLRPC.DocumentAttribute> attributes, boolean photo, long randomId) {
        file.delete();
        return null;
    }

    /** XMPP rooms, each shown as a supergroup. */
    default List<TLRPC.Chat> roomChats() {
        return Collections.emptyList();
    }

    default TLRPC.TL_messages_chatFull fullRoom(long channelId) {
        return null;
    }

    default TLRPC.TL_channels_channelParticipants roomParticipants(long channelId, TLRPC.ChannelParticipantsFilter filter, int offset, int limit) {
        return null;
    }

    default TLRPC.TL_channels_channelParticipant roomParticipant(long channelId, long userId) {
        return null;
    }

    default TLObject roomDifference(long channelId, int fromPts) {
        return null;
    }

    default TLRPC.TL_messages_affectedMessages deleteRoomMessages(long channelId, List<Integer> ids) {
        return null;
    }

    default TLObject createRoom(String title, List<Long> userIds) {
        return null;
    }

    default TLObject inviteToRoom(long channelId, List<Long> userIds) {
        return null;
    }

    default TLObject banFromRoom(long channelId, long userId, boolean banned) {
        return null;
    }

    default TLObject setRoomAdmin(long channelId, long userId, boolean admin) {
        return null;
    }

    default TLObject leaveRoom(long channelId) {
        return null;
    }

    default TLObject destroyRoom(long channelId) {
        return null;
    }

    default TLObject renameRoom(long channelId, String title) {
        return null;
    }

    default TLObject describeRoom(long channelId, String about) {
        return null;
    }

    default TLObject setRoomPhoto(long channelId, java.io.File image) {
        return null;
    }

    /** Returns the engine's call handling, or null when the backend has no calls. */
    default XmppCalls calls() {
        return null;
    }

    /** Runs once a chat's history is there to read, which may first need fetching from the server. */
    default void whenHistoryReady(long peerId, Runnable ready) {
        ready.run();
    }

    /** Whether this peer is someone Telegram may open a chat with even before any message exists. */
    default boolean isKnownUser(long peerId) {
        return false;
    }

    /** People banned from a room, shown as the room's removed users. */
    default TLObject roomBanned(long channelId, int offset, int limit) {
        return new TLRPC.TL_channels_channelParticipants();
    }
}
