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
import org.webrtc.VideoSink;

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
        current.whenReady(() -> queue.postRunnable(() -> {
            TLObject response = handle(request);
            if (response != null) {
                reply.run(response, null);
            } else {
                replyUnsupported(request, reply);
            }
        }));
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
            return backend.sendText(send.peer.user_id, send.message);
        } else if (request instanceof TLRPC.TL_messages_readHistory) {
            TLRPC.TL_messages_readHistory read = (TLRPC.TL_messages_readHistory) request;
            return backend.markRead(read.peer.user_id, read.max_id);
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
            return result;
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
        calls.discardCall(discard.peer.id, discard.reason);
        return new TLRPC.TL_updates();
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

    private TLObject file(TLRPC.TL_upload_getFile request) {
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
        return difference;
    }

    private TLObject dialogs(TLRPC.TL_messages_getDialogs request) {
        TLRPC.TL_messages_dialogs result = new TLRPC.TL_messages_dialogs();
        if (request.offset_id == 0 && request.offset_date == 0) {
            result.dialogs.addAll(backend.dialogs());
            result.messages.addAll(backend.topMessages());
            result.users.addAll(backend.users());
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
                long userId = ((TLRPC.TL_inputDialogPeer) inputPeer).peer.user_id;
                for (TLRPC.Dialog dialog : dialogs) {
                    if (dialog.peer.user_id == userId) {
                        result.dialogs.add(dialog);
                    }
                }
            }
        }
        result.messages.addAll(backend.topMessages());
        result.users.addAll(backend.users());
        return result;
    }

    private TLObject history(TLRPC.TL_messages_getHistory request) {
        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        result.messages.addAll(backend.history(request.peer.user_id, request.offset_id, request.add_offset, request.limit, request.max_id, request.min_id));
        result.users.addAll(backend.users());
        return result;
    }
}
