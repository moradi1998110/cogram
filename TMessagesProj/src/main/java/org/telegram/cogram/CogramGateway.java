package org.telegram.cogram;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

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

    private static final CogramGateway[] instances = new CogramGateway[UserConfig.MAX_ACCOUNT_COUNT];

    public static synchronized CogramGateway getInstance(int account) {
        if (instances[account] == null) {
            instances[account] = new CogramGateway(account);
        }
        return instances[account];
    }

    private final int account;
    private final GatewayBackend backend;

    private CogramGateway(int account) {
        this.account = account;
        backend = new DemoBackend(account);
        backend.setUpdatesListener(updates -> Utilities.stageQueue.postRunnable(() ->
                AccountInstance.getInstance(this.account).getMessagesController().processUpdates(updates, false)));
    }

    /** Signs the first account in locally, so the app opens on the chat list. */
    public void ensureSignedIn(UserConfig userConfig) {
        if (account == 0 && userConfig.getCurrentUser() == null) {
            userConfig.setCurrentUser(backend.selfUser());
            userConfig.saveConfig(false);
        }
    }

    public void dispatch(TLObject request, Reply reply) {
        TLObject response = handle(request);
        if (response != null) {
            reply.run(response, null);
            return;
        }
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
            TLRPC.TL_updates_differenceEmpty difference = new TLRPC.TL_updates_differenceEmpty();
            difference.date = now();
            return difference;
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
        }
        return null;
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
        result.messages.addAll(backend.history(request.peer.user_id, request.offset_id, request.limit, request.max_id, request.min_id));
        result.users.addAll(backend.users());
        return result;
    }
}
