package org.telegram.cogram;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.messenger.voip.NativeInstance;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.tgnet.tl.TL_update;
import org.webrtc.VideoSink;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.services.ExternalCallUi;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers TL requests inside the app, in place of Telegram's native network layer.
 * Requests without a handler fail with {@link #ERROR_UNSUPPORTED} and are logged, which shows
 * what each screen still needs.
 */
public final class CogramGateway {

    public static final boolean ENABLED = true;
    public static final String ERROR_UNSUPPORTED = "COGRAM_UNSUPPORTED";

    public interface Reply {
        void run(TLObject response, TLRPC.TL_error error);
    }

    private static final String PREFERENCES = "cogram_gateway";
    private static final String KEY_DEMO = "demo";

    private static final CogramGateway[] instances = new CogramGateway[UserConfig.MAX_ACCOUNT_COUNT];

    public static synchronized CogramGateway getInstance(int account) {
        if (instances[account] == null) {
            instances[account] = new CogramGateway(account);
        }
        return instances[account];
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE);
    }

    /** The emulator check turns the demo on by writing this preference, so the app needs no server. */
    private static boolean isDemoSelected() {
        return preferences().getBoolean(KEY_DEMO, false);
    }

    public static boolean needsLogin() {
        return ENABLED && !isDemoSelected() && !XmppBackend.hasAccount(ApplicationLoader.applicationContext);
    }

    private final int account;
    private final DispatchQueue queue = new DispatchQueue("cogramGateway");
    private final DispatchQueue mediaQueue = new DispatchQueue("cogramGatewayMedia");
    private final UploadParts uploads = new UploadParts();
    private volatile GatewayBackend backend;

    private CogramGateway(int account) {
        this.account = account;
        selectBackend();
    }

    private void selectBackend() {
        if (account != 0 || backend != null || needsLogin()) {
            return;
        }
        GatewayBackend selected = isDemoSelected() ? new DemoBackend(account) : new XmppBackend();
        selected.setUpdatesListener(updates -> Utilities.stageQueue.postRunnable(() -> {
            AccountInstance instance = AccountInstance.getInstance(this.account);
            ArrayList<TLRPC.User> users = new ArrayList<>(selected.users());
            instance.getMessagesController().putUsers(users, false);
            instance.getMessagesController().processUpdates(updates, false);
        }));
        applyPrivacy();
        backend = selected;
    }

    /** Signs the first account in locally, so the app opens on the chat list. */
    public void ensureSignedIn(UserConfig userConfig) {
        if (backend != null && userConfig.getCurrentUser() == null) {
            userConfig.setCurrentUser(backend.selfUser());
            userConfig.saveConfig(false);
        }
    }

    /** Connects the account just signed in to, and returns it as the authorization Telegram's login screen expects. */
    TLRPC.TL_auth_authorization completeSignIn() {
        selectBackend();
        TLRPC.TL_auth_authorization authorization = new TLRPC.TL_auth_authorization();
        authorization.user = backend.selfUser();
        return authorization;
    }

    private TLObject signOut() {
        GatewayBackend current = backend;
        backend = null;
        current.signOut();
        preferences().edit().remove(KEY_DEMO).apply();
        return new TLRPC.TL_auth_loggedOut();
    }

    public void dispatch(TLObject request, Reply reply) {
        GatewayBackend current = backend;
        if (current == null) {
            replyUnsupported(request, reply);
            return;
        }
        // A file may first have to be fetched, which must not hold up every other request.
        DispatchQueue target = isMediaRequest(request) ? mediaQueue : queue;
        current.whenReady(() -> target.postRunnable(() -> {
            TLObject response;
            try {
                response = handle(request);
            } catch (GatewayError e) {
                TLRPC.TL_error error = new TLRPC.TL_error();
                error.code = 400;
                error.text = e.getMessage();
                reply.run(null, error);
                return;
            }
            if (response instanceof DeferredReply) {
                ((DeferredReply) response).attach(reply);
            } else if (response != null) {
                reply.run(response, null);
            } else {
                replyUnsupported(request, reply);
            }
        }));
    }

    /** A request Telegram should see fail with one of its own error codes, which its screens explain to the user. */
    private static final class GatewayError extends RuntimeException {
        GatewayError(String text) {
            super(text);
        }
    }

    private static void replyUnsupported(TLObject request, Reply reply) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("cogram: unsupported request " + request.getClass().getSimpleName());
        }
        TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = 400;
        error.text = ERROR_UNSUPPORTED;
        reply.run(null, error);
    }

    private TLObject handle(TLObject request) {
        TLObject stickers = noStickers(request);
        if (stickers != null) {
            return stickers;
        }
        TLObject roomResponse = room(request);
        if (roomResponse != null) {
            return roomResponse;
        }
        if (request instanceof TLRPC.TL_updates_getState) {
            return state();
        } else if (request instanceof TLRPC.TL_updates_getDifference) {
            return difference((TLRPC.TL_updates_getDifference) request);
        } else if (request instanceof TLRPC.TL_messages_getDialogs) {
            return dialogs((TLRPC.TL_messages_getDialogs) request);
        } else if (request instanceof TLRPC.TL_messages_getPinnedDialogs) {
            TLRPC.TL_messages_peerDialogs pinned = new TLRPC.TL_messages_peerDialogs();
            pinned.state = state();
            return pinned;
        } else if (request instanceof TLRPC.TL_messages_getPeerDialogs) {
            return peerDialogs((TLRPC.TL_messages_getPeerDialogs) request);
        } else if (request instanceof TLRPC.TL_messages_getHistory) {
            return history((TLRPC.TL_messages_getHistory) request);
        } else if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage send = (TLRPC.TL_messages_sendMessage) request;
            int replyTo = send.reply_to instanceof TLRPC.TL_inputReplyToMessage ? ((TLRPC.TL_inputReplyToMessage) send.reply_to).reply_to_msg_id : 0;
            return backend.sendText(peerId(send.peer), send.message, replyTo, send.random_id);
        } else if (request instanceof TLRPC.TL_messages_editMessage) {
            TLRPC.TL_messages_editMessage edit = (TLRPC.TL_messages_editMessage) request;
            if (edit.message == null || edit.media != null) {
                throw new GatewayError("MESSAGE_EDIT_TIME_EXPIRED");
            }
            TLRPC.Updates updates = backend.editText(peerId(edit.peer), edit.id, edit.message);
            if (updates == null) {
                throw new GatewayError("MESSAGE_EDIT_TIME_EXPIRED");
            }
            return updates;
        } else if (request instanceof TLRPC.TL_messages_sendReaction) {
            TLRPC.TL_messages_sendReaction reaction = (TLRPC.TL_messages_sendReaction) request;
            ArrayList<String> emojis = new ArrayList<>();
            for (TLRPC.Reaction chosen : reaction.reaction) {
                if (chosen instanceof TLRPC.TL_reactionEmoji) {
                    emojis.add(((TLRPC.TL_reactionEmoji) chosen).emoticon);
                }
            }
            return backend.react(peerId(reaction.peer), reaction.msg_id, emojis);
        } else if (request instanceof TLRPC.TL_messages_deleteMessages) {
            TLRPC.TL_messages_deleteMessages delete = (TLRPC.TL_messages_deleteMessages) request;
            return backend.deleteMessages(delete.id, delete.revoke);
        } else if (request instanceof TLRPC.TL_messages_forwardMessages) {
            TLRPC.TL_messages_forwardMessages forward = (TLRPC.TL_messages_forwardMessages) request;
            return backend.forward(forward.id, forward.random_id, peerId(forward.to_peer));
        } else if (request instanceof TLRPC.TL_messages_getOutboxReadDate) {
            TLRPC.TL_messages_getOutboxReadDate read = (TLRPC.TL_messages_getOutboxReadDate) request;
            int date = backend.readDate(peerId(read.peer), read.msg_id);
            if (date == 0) {
                // Telegram then shows the message as read without a time.
                throw new GatewayError("MESSAGE_TOO_OLD");
            }
            TLRPC.TL_outboxReadDate result = new TLRPC.TL_outboxReadDate();
            result.date = date;
            return result;
        } else if (request instanceof TLRPC.TL_messages_getMessages) {
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            result.messages.addAll(backend.messages(((TLRPC.TL_messages_getMessages) request).id));
            result.users.addAll(backend.users());
            result.chats.addAll(backend.roomChats());
            return result;
        } else if (request instanceof TLRPC.TL_messages_sendMedia) {
            return sendMedia((TLRPC.TL_messages_sendMedia) request);
        } else if (request instanceof TLRPC.TL_messages_readHistory) {
            TLRPC.TL_messages_readHistory read = (TLRPC.TL_messages_readHistory) request;
            return backend.markRead(peerId(read.peer), read.max_id);
        } else if (request instanceof TLRPC.TL_messages_setTyping) {
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_auth_logOut) {
            return signOut();
        } else if (request instanceof TLRPC.TL_users_getUsers) {
            return users((TLRPC.TL_users_getUsers) request);
        } else if (request instanceof TLRPC.TL_users_getFullUser) {
            return fullUser(userId(((TLRPC.TL_users_getFullUser) request).id));
        } else if (request instanceof TLRPC.TL_photos_getUserPhotos) {
            return userPhotos((TLRPC.TL_photos_getUserPhotos) request);
        } else if (request instanceof TLRPC.TL_upload_getFile) {
            return file((TLRPC.TL_upload_getFile) request);
        } else if (request instanceof TL_account.getPrivacy) {
            return privacyRules(((TL_account.getPrivacy) request).key);
        } else if (request instanceof TL_account.setPrivacy) {
            return setPrivacy((TL_account.setPrivacy) request);
        } else if (request instanceof TL_account.updateProfile) {
            TL_account.updateProfile update = (TL_account.updateProfile) request;
            TLRPC.User self = backend.selfUser();
            String firstName = update.first_name != null ? update.first_name : self.first_name;
            String lastName = update.last_name != null ? update.last_name : self.last_name;
            return backend.updateName(firstName, lastName);
        } else if (request instanceof TLRPC.TL_upload_saveFilePart) {
            TLRPC.TL_upload_saveFilePart part = (TLRPC.TL_upload_saveFilePart) request;
            uploads.savePart(part.file_id, part.file_part, part.bytes);
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_upload_saveBigFilePart) {
            TLRPC.TL_upload_saveBigFilePart part = (TLRPC.TL_upload_saveBigFilePart) request;
            uploads.savePart(part.file_id, part.file_part, part.bytes);
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_photos_uploadProfilePhoto) {
            return uploadProfilePhoto((TLRPC.TL_photos_uploadProfilePhoto) request);
        } else if (request instanceof TL_phone.requestCall || request instanceof TL_phone.acceptCall
                || request instanceof TL_phone.receivedCall || request instanceof TL_phone.discardCall) {
            return call(request);
        } else if (request instanceof TLRPC.TL_messages_search
                && ((TLRPC.TL_messages_search) request).filter instanceof TLRPC.TL_inputMessagesFilterPhoneCalls) {
            TLRPC.TL_messages_search search = (TLRPC.TL_messages_search) request;
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            result.messages.addAll(backend.callHistory(search.offset_id, search.limit));
            result.users.addAll(backend.users());
            result.chats.addAll(backend.roomChats());
            return result;
        } else if (request instanceof TLRPC.TL_contacts_getContacts) {
            TLRPC.TL_contacts_contacts result = new TLRPC.TL_contacts_contacts();
            result.contacts.addAll(backend.contacts());
            result.users.addAll(backend.users());
            return result;
        } else if (request instanceof TLRPC.TL_contacts_getStatuses) {
            Vector<TLRPC.TL_contactStatus> result = new Vector<>(TLRPC.TL_contactStatus::TLdeserialize);
            for (TLRPC.TL_contact contact : backend.contacts()) {
                TLRPC.User user = backend.user(contact.user_id);
                if (user != null && user.status != null) {
                    TLRPC.TL_contactStatus status = new TLRPC.TL_contactStatus();
                    status.user_id = user.id;
                    status.status = user.status;
                    result.objects.add(status);
                }
            }
            return result;
        } else if (request instanceof TLRPC.TL_contacts_importContacts) {
            // XMPP has no phone numbers, so the address book never matches anyone.
            return new TLRPC.TL_contacts_importedContacts();
        } else if (request instanceof TLRPC.TL_contacts_resetSaved) {
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_contacts_search) {
            TLRPC.TL_contacts_found result = new TLRPC.TL_contacts_found();
            for (TLRPC.User user : backend.search(((TLRPC.TL_contacts_search) request).q)) {
                TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
                peer.user_id = user.id;
                (user.contact ? result.my_results : result.results).add(peer);
                result.users.add(user);
            }
            return result;
        } else if (request instanceof TLRPC.TL_contacts_resolveUsername) {
            TLRPC.User user = backend.resolve(((TLRPC.TL_contacts_resolveUsername) request).username);
            if (user == null) {
                throw new GatewayError("USERNAME_NOT_OCCUPIED");
            }
            TLRPC.TL_contacts_resolvedPeer result = new TLRPC.TL_contacts_resolvedPeer();
            TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
            peer.user_id = user.id;
            result.peer = peer;
            result.users.add(user);
            return result;
        } else if (request instanceof TLRPC.TL_contacts_addContact) {
            TLRPC.TL_contacts_addContact add = (TLRPC.TL_contacts_addContact) request;
            TLRPC.User user = backend.addContact(userId(add.id), add.first_name, add.last_name);
            if (user == null) {
                return null;
            }
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.users.add(user);
            updates.date = now();
            return updates;
        } else if (request instanceof TLRPC.TL_contacts_deleteContacts) {
            for (TLRPC.InputUser inputUser : ((TLRPC.TL_contacts_deleteContacts) request).id) {
                backend.deleteContact(userId(inputUser));
            }
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = now();
            return updates;
        } else if (request instanceof TLRPC.TL_contacts_block || request instanceof TLRPC.TL_contacts_unblock) {
            boolean block = request instanceof TLRPC.TL_contacts_block;
            TLRPC.InputPeer peer = block ? ((TLRPC.TL_contacts_block) request).id : ((TLRPC.TL_contacts_unblock) request).id;
            backend.setBlocked(peer.user_id, block);
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_messages_getPeerSettings) {
            TLRPC.TL_messages_peerSettings result = new TLRPC.TL_messages_peerSettings();
            result.settings = backend.peerSettings(((TLRPC.TL_messages_getPeerSettings) request).peer.user_id);
            return result;
        } else if (request instanceof TLRPC.TL_messages_hidePeerSettingsBar || request instanceof TLRPC.TL_messages_reportSpam) {
            // Telegram remembers a closed bar itself, and XMPP has no spam reports, so blocking is all there is.
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TL_phone.setCallRating) {
            return new TLRPC.TL_updates();
        } else if (request instanceof TL_phone.saveCallDebug || request instanceof TL_phone.sendSignalingData) {
            return new TLRPC.TL_boolTrue();
        }
        return null;
    }

    private TLObject call(TLObject request) {
        XmppCalls calls = backend.calls();
        if (calls == null) {
            return null;
        }
        if (request instanceof TL_phone.requestCall) {
            TL_phone.requestCall requestCall = (TL_phone.requestCall) request;
            return calls.requestCall(userId(requestCall.user_id), requestCall.video);
        } else if (request instanceof TL_phone.acceptCall) {
            return calls.acceptCall(((TL_phone.acceptCall) request).peer.id);
        } else if (request instanceof TL_phone.receivedCall) {
            return calls.receivedCall(((TL_phone.receivedCall) request).peer.id) ? new TLRPC.TL_boolTrue() : null;
        }
        TL_phone.discardCall discard = (TL_phone.discardCall) request;
        TL_update.TL_updatePhoneCall ended = new TL_update.TL_updatePhoneCall();
        ended.phone_call = calls.discardCall(discard.peer.id, discard.reason);
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.updates.add(ended);
        updates.date = now();
        return updates;
    }

    /** The user's full XMPP address, which Telegram's screens show only without the server. */
    public String addressOf(long userId) {
        GatewayBackend current = backend;
        return current == null ? null : current.address(userId);
    }

    /** Creates the engine for a call Telegram's call service is starting, or null when the call is gone. */
    public NativeInstance createCallInstance(long callId, VideoSink remoteSink) {
        GatewayBackend current = backend;
        XmppCalls calls = current == null ? null : current.calls();
        return calls == null ? null : calls.createInstance(callId, remoteSink);
    }

    /** The key behind the emoji Telegram shows, so both users can compare their calls' encryption. */
    public static byte[] callEncryptionKey(NativeInstance instance) {
        return instance instanceof XmppCallInstance ? ((XmppCallInstance) instance).encryptionKey() : null;
    }

    private long userId(TLRPC.InputUser inputUser) {
        if (inputUser instanceof TLRPC.TL_inputUser) {
            return inputUser.user_id;
        }
        return backend.selfUser().id;
    }

    private TLObject users(TLRPC.TL_users_getUsers request) {
        Vector<TLRPC.User> result = new Vector<>(TLRPC.User::TLdeserialize);
        for (TLRPC.InputUser inputUser : request.id) {
            TLRPC.User user = backend.user(userId(inputUser));
            if (user != null) {
                result.objects.add(user);
            }
        }
        return result;
    }

    private TLObject fullUser(long userId) {
        TLRPC.User user = backend.user(userId);
        if (user == null) {
            return null;
        }
        TLRPC.TL_users_userFull result = new TLRPC.TL_users_userFull();
        result.full_user = backend.fullUser(userId);
        result.users.add(user);
        return result;
    }

    private TLObject userPhotos(TLRPC.TL_photos_getUserPhotos request) {
        TLRPC.TL_photos_photos result = new TLRPC.TL_photos_photos();
        if (request.offset == 0 && request.max_id == 0) {
            result.photos.addAll(backend.userPhotos(userId(request.user_id)));
        }
        return result;
    }

    /**
     * XMPP has no stickers, and Telegram retries a failed sticker request every second for as long
     * as the app runs, so these get empty answers that count as loaded instead of an error.
     */
    private static TLObject noStickers(TLObject request) {
        if (request instanceof TLRPC.TL_messages_getFeaturedStickers || request instanceof TLRPC.TL_messages_getFeaturedEmojiStickers) {
            return new TLRPC.TL_messages_featuredStickers();
        } else if (request instanceof TLRPC.TL_messages_getArchivedStickers) {
            return new TLRPC.TL_messages_archivedStickers();
        } else if (request instanceof TLRPC.TL_messages_getAllStickers || request instanceof TLRPC.TL_messages_getEmojiStickers
                || request instanceof TLRPC.TL_messages_getMaskStickers) {
            return new TLRPC.TL_messages_allStickers();
        } else if (request instanceof TLRPC.TL_messages_getStickerSet) {
            TLRPC.InputStickerSet input = ((TLRPC.TL_messages_getStickerSet) request).stickerset;
            TLRPC.TL_stickerSet set = new TLRPC.TL_stickerSet();
            set.title = "";
            if (input instanceof TLRPC.TL_inputStickerSetShortName) {
                set.short_name = ((TLRPC.TL_inputStickerSetShortName) input).short_name;
                set.id = set.short_name.hashCode();
            } else {
                set.short_name = "";
                set.id = input == null ? 0 : input.id;
                set.access_hash = input == null ? 0 : input.access_hash;
            }
            TLRPC.TL_messages_stickerSet result = new TLRPC.TL_messages_stickerSet();
            result.set = set;
            return result;
        }
        return null;
    }

    /** A user or a room, whose numbers are drawn from one sequence and so never collide. */
    private static long peerId(TLRPC.InputPeer peer) {
        return peer.channel_id != 0 ? peer.channel_id : peer.user_id;
    }

    private List<Long> userIds(List<TLRPC.InputUser> users) {
        ArrayList<Long> ids = new ArrayList<>();
        for (TLRPC.InputUser user : users) {
            ids.add(userId(user));
        }
        return ids;
    }

    /** The requests of Telegram's supergroup screens, answered from the XMPP room behind each one. */
    private TLObject room(TLObject request) {
        if (request instanceof TLRPC.TL_messages_createChat) {
            TLRPC.TL_messages_createChat create = (TLRPC.TL_messages_createChat) request;
            return backend.createRoom(create.title, userIds(create.users));
        } else if (request instanceof TLRPC.TL_channels_getFullChannel) {
            return backend.fullRoom(((TLRPC.TL_channels_getFullChannel) request).channel.channel_id);
        } else if (request instanceof TLRPC.TL_channels_getChannels) {
            TLRPC.TL_messages_chats chats = new TLRPC.TL_messages_chats();
            chats.chats.addAll(backend.roomChats());
            return chats;
        } else if (request instanceof TLRPC.TL_channels_getParticipants) {
            TLRPC.TL_channels_getParticipants get = (TLRPC.TL_channels_getParticipants) request;
            return backend.roomParticipants(get.channel.channel_id, get.filter, get.offset, get.limit);
        } else if (request instanceof TLRPC.TL_channels_getParticipant) {
            TLRPC.TL_channels_getParticipant get = (TLRPC.TL_channels_getParticipant) request;
            return backend.roomParticipant(get.channel.channel_id, peerId(get.participant));
        } else if (request instanceof TLRPC.TL_channels_getMessages) {
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            result.messages.addAll(backend.messages(((TLRPC.TL_channels_getMessages) request).id));
            result.users.addAll(backend.users());
            result.chats.addAll(backend.roomChats());
            return result;
        } else if (request instanceof TLRPC.TL_channels_readHistory) {
            TLRPC.TL_channels_readHistory read = (TLRPC.TL_channels_readHistory) request;
            backend.markRead(read.channel.channel_id, read.max_id);
            return new TLRPC.TL_boolTrue();
        } else if (request instanceof TLRPC.TL_channels_deleteMessages) {
            TLRPC.TL_channels_deleteMessages delete = (TLRPC.TL_channels_deleteMessages) request;
            return backend.deleteRoomMessages(delete.channel.channel_id, delete.id);
        } else if (request instanceof TLRPC.TL_updates_getChannelDifference) {
            TLRPC.TL_updates_getChannelDifference difference = (TLRPC.TL_updates_getChannelDifference) request;
            return backend.roomDifference(difference.channel.channel_id, difference.pts);
        } else if (request instanceof TLRPC.TL_channels_inviteToChannel) {
            TLRPC.TL_channels_inviteToChannel invite = (TLRPC.TL_channels_inviteToChannel) request;
            return backend.inviteToRoom(invite.channel.channel_id, userIds(invite.users));
        } else if (request instanceof TLRPC.TL_channels_editBanned) {
            TLRPC.TL_channels_editBanned ban = (TLRPC.TL_channels_editBanned) request;
            // XMPP has no partial restriction, and clearing one would remove the member's place in the room.
            return ban.banned_rights.view_messages ? backend.banFromRoom(ban.channel.channel_id, peerId(ban.participant), true) : null;
        } else if (request instanceof TLRPC.TL_channels_editAdmin) {
            TLRPC.TL_channels_editAdmin admin = (TLRPC.TL_channels_editAdmin) request;
            TLRPC.TL_chatAdminRights rights = admin.admin_rights;
            boolean any = rights.change_info || rights.delete_messages || rights.ban_users || rights.invite_users
                    || rights.pin_messages || rights.add_admins || rights.other;
            return backend.setRoomAdmin(admin.channel.channel_id, userId(admin.user_id), any);
        } else if (request instanceof TLRPC.TL_channels_leaveChannel) {
            return backend.leaveRoom(((TLRPC.TL_channels_leaveChannel) request).channel.channel_id);
        } else if (request instanceof TLRPC.TL_channels_deleteChannel) {
            return backend.destroyRoom(((TLRPC.TL_channels_deleteChannel) request).channel.channel_id);
        } else if (request instanceof TLRPC.TL_channels_editTitle) {
            TLRPC.TL_channels_editTitle edit = (TLRPC.TL_channels_editTitle) request;
            return backend.renameRoom(edit.channel.channel_id, edit.title);
        } else if (request instanceof TLRPC.TL_messages_editChatAbout) {
            TLRPC.TL_messages_editChatAbout edit = (TLRPC.TL_messages_editChatAbout) request;
            return backend.describeRoom(peerId(edit.peer), edit.about);
        } else if (request instanceof TLRPC.TL_channels_editPhoto) {
            TLRPC.TL_channels_editPhoto edit = (TLRPC.TL_channels_editPhoto) request;
            File image = edit.photo instanceof TLRPC.TL_inputChatUploadedPhoto ? uploads.takeFile(((TLRPC.TL_inputChatUploadedPhoto) edit.photo).file.id) : null;
            return image == null ? null : backend.setRoomPhoto(edit.channel.channel_id, image);
        }
        return null;
    }

    private static final String PRIVACY_PREFIX = "privacy_";
    private static final int PRIVACY_EVERYBODY = 0;
    private static final int PRIVACY_CONTACTS = 1;
    private static final int PRIVACY_NOBODY = 2;

    /** Being added to groups and being rung reach us from strangers, so both start at contacts only. */
    private static int privacyMode(TLRPC.InputPrivacyKey key) {
        boolean guarded = key instanceof TLRPC.TL_inputPrivacyKeyChatInvite || key instanceof TLRPC.TL_inputPrivacyKeyPhoneCall;
        return preferences().getInt(PRIVACY_PREFIX + key.getClass().getSimpleName(), guarded ? PRIVACY_CONTACTS : PRIVACY_EVERYBODY);
    }

    /** Telegram's privacy screens decide what the engine accepts. */
    private static void applyPrivacy() {
        AppSettings settings = new AppSettings(ApplicationLoader.applicationContext);
        settings.setAcceptInvitesFromStrangers(privacyMode(new TLRPC.TL_inputPrivacyKeyChatInvite()) == PRIVACY_EVERYBODY);
        settings.setUseRelays(privacyMode(new TLRPC.TL_inputPrivacyKeyPhoneP2P()) == PRIVACY_NOBODY);
        int calls = privacyMode(new TLRPC.TL_inputPrivacyKeyPhoneCall());
        ExternalCallUi.setCallPolicy(calls == PRIVACY_EVERYBODY ? ExternalCallUi.CALLS_EVERYONE
                : calls == PRIVACY_CONTACTS ? ExternalCallUi.CALLS_CONTACTS : ExternalCallUi.CALLS_NOBODY);
    }

    private static TL_account.privacyRules privacyRules(TLRPC.InputPrivacyKey key) {
        int mode = privacyMode(key);
        TL_account.privacyRules rules = new TL_account.privacyRules();
        rules.rules.add(mode == PRIVACY_EVERYBODY ? new TLRPC.TL_privacyValueAllowAll()
                : mode == PRIVACY_CONTACTS ? new TLRPC.TL_privacyValueAllowContacts() : new TLRPC.TL_privacyValueDisallowAll());
        return rules;
    }

    private static TL_account.privacyRules setPrivacy(TL_account.setPrivacy request) {
        int mode = PRIVACY_NOBODY;
        for (TLRPC.InputPrivacyRule rule : request.rules) {
            if (rule instanceof TLRPC.TL_inputPrivacyValueAllowAll) {
                mode = PRIVACY_EVERYBODY;
            } else if (rule instanceof TLRPC.TL_inputPrivacyValueAllowContacts && mode != PRIVACY_EVERYBODY) {
                mode = PRIVACY_CONTACTS;
            }
        }
        preferences().edit().putInt(PRIVACY_PREFIX + request.key.getClass().getSimpleName(), mode).apply();
        applyPrivacy();
        return privacyRules(request.key);
    }

    private static boolean isMediaRequest(TLObject request) {
        if (!(request instanceof TLRPC.TL_upload_getFile)) {
            return false;
        }
        TLRPC.InputFileLocation location = ((TLRPC.TL_upload_getFile) request).location;
        return (location instanceof TLRPC.TL_inputDocumentFileLocation || location instanceof TLRPC.TL_inputPhotoFileLocation)
                && XmppBackend.isMediaId(location.id);
    }

    private TLObject sendMedia(TLRPC.TL_messages_sendMedia request) {
        TLRPC.InputMedia media = request.media;
        boolean photo = media instanceof TLRPC.TL_inputMediaUploadedPhoto;
        if (!photo && !(media instanceof TLRPC.TL_inputMediaUploadedDocument) || media.file == null) {
            return null;
        }
        File file = uploads.takeFile(media.file.id);
        if (file == null) {
            return null;
        }
        String mime = photo ? "image/jpeg" : media.mime_type;
        if (mime == null || mime.isEmpty()) {
            mime = "application/octet-stream";
        }
        return backend.sendFile(peerId(request.peer), file, mime, photo ? new ArrayList<>() : media.attributes, photo, request.random_id);
    }

    private TLObject file(TLRPC.TL_upload_getFile request) {
        if (isMediaRequest(request)) {
            return backend.mediaFile(request.location.id, request.offset, request.limit);
        }
        long photoId;
        if (request.location instanceof TLRPC.TL_inputPeerPhotoFileLocation) {
            photoId = ((TLRPC.TL_inputPeerPhotoFileLocation) request.location).photo_id;
        } else if (request.location instanceof TLRPC.TL_inputPhotoFileLocation) {
            photoId = request.location.id;
        } else {
            return null;
        }
        return backend.photoFile(photoId, request.offset, request.limit);
    }

    private TLObject uploadProfilePhoto(TLRPC.TL_photos_uploadProfilePhoto request) {
        if (request.file == null) {
            return null;
        }
        byte[] image = uploads.take(request.file.id);
        if (image == null) {
            return null;
        }
        TLRPC.Photo photo = backend.updatePhoto(image);
        if (photo == null) {
            return null;
        }
        TLRPC.TL_photos_photo result = new TLRPC.TL_photos_photo();
        result.photo = photo;
        result.users.add(backend.selfUser());
        return result;
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    private TLRPC.TL_updates_state state() {
        TLRPC.TL_updates_state state = new TLRPC.TL_updates_state();
        state.pts = backend.pts();
        state.date = now();
        return state;
    }

    /** An empty difference leaves the app's pts as it is, and a stale pts makes it drop every later update. */
    private TLObject difference(TLRPC.TL_updates_getDifference request) {
        if (request.pts == backend.pts()) {
            TLRPC.TL_updates_differenceEmpty empty = new TLRPC.TL_updates_differenceEmpty();
            empty.date = now();
            return empty;
        }
        TLRPC.TL_updates_difference difference = new TLRPC.TL_updates_difference();
        difference.state = state();
        difference.new_messages.addAll(backend.messagesAfter(request.pts));
        difference.users.addAll(backend.users());
        difference.chats.addAll(backend.roomChats());
        return difference;
    }

    private TLObject dialogs(TLRPC.TL_messages_getDialogs request) {
        TLRPC.TL_messages_dialogs result = new TLRPC.TL_messages_dialogs();
        if (request.offset_id == 0 && request.offset_date == 0) {
            result.dialogs.addAll(backend.dialogs());
            result.messages.addAll(backend.topMessages());
            result.users.addAll(backend.users());
            result.chats.addAll(backend.roomChats());
        }
        result.count = result.dialogs.size();
        return result;
    }

    private TLObject peerDialogs(TLRPC.TL_messages_getPeerDialogs request) {
        TLRPC.TL_messages_peerDialogs result = new TLRPC.TL_messages_peerDialogs();
        result.state = state();
        List<TLRPC.Dialog> dialogs = backend.dialogs();
        for (TLRPC.InputDialogPeer inputPeer : request.peers) {
            if (inputPeer instanceof TLRPC.TL_inputDialogPeer) {
                long peer = peerId(((TLRPC.TL_inputDialogPeer) inputPeer).peer);
                for (TLRPC.Dialog dialog : dialogs) {
                    if (dialog.peer.user_id == peer || dialog.peer.channel_id == peer) {
                        result.dialogs.add(dialog);
                    }
                }
            }
        }
        result.messages.addAll(backend.topMessages());
        result.users.addAll(backend.users());
        result.chats.addAll(backend.roomChats());
        return result;
    }

    private TLObject history(TLRPC.TL_messages_getHistory request) {
        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        result.messages.addAll(backend.history(peerId(request.peer), request.offset_id, request.add_offset, request.limit, request.max_id, request.min_id));
        result.users.addAll(backend.users());
        result.chats.addAll(backend.roomChats());
        return result;
    }
}
