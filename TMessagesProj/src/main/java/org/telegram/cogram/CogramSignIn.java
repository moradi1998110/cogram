package org.telegram.cogram;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;

/** Signs in to an XMPP account for Telegram's login screen, and reports the result on the main thread. */
public final class CogramSignIn {

    public interface Callback {
        void onSignedIn(TLRPC.TL_auth_authorization authorization);

        void onWrongPassword();

        void onFailed(String state);
    }

    private static final int POLL_MS = 1000;
    private static final int TIMEOUT_MS = 60000;

    private final Context context = ApplicationLoader.applicationContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int currentAccount;
    private final Jid address;
    private final String password;
    private final Callback callback;
    private XmppConnectionService service;
    private Account account;
    private int waitedMs;
    private boolean cancelled;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            XmppConnectionService connected = ((XmppConnectionService.XmppConnectionBinder) binder).getService();
            new Thread(() -> {
                try {
                    connected.restoredFromDatabaseLatch.await();
                } catch (InterruptedException e) {
                    return;
                }
                handler.post(() -> {
                    service = connected;
                    createAccount();
                });
            }).start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    private CogramSignIn(int currentAccount, Jid address, String password, Callback callback) {
        this.currentAccount = currentAccount;
        this.address = address;
        this.password = password;
        this.callback = callback;
    }

    /** Returns the bare address typed by the user, or null when it is not a full user@server address. */
    public static String parseAddress(String text) {
        Jid jid = toJid(text);
        return jid == null ? null : jid.toString();
    }

    private static Jid toJid(String text) {
        try {
            Jid jid = Jid.ofUserInput(text.trim());
            if (jid.getLocal() == null || jid.getDomain().toString().isEmpty()) {
                return null;
            }
            return jid.asBareJid();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static CogramSignIn start(int currentAccount, String address, String password, Callback callback) {
        CogramSignIn signIn = new CogramSignIn(currentAccount, toJid(address), password, callback);
        Intent intent = new Intent(signIn.context, XmppConnectionService.class);
        try {
            signIn.context.startService(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
        signIn.context.bindService(intent, signIn.connection, Context.BIND_AUTO_CREATE);
        return signIn;
    }

    /** Stops waiting and removes the half-created account, as when the user leaves the password page. */
    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        handler.removeCallbacksAndMessages(null);
        removeAccount();
        context.unbindService(connection);
    }

    private void createAccount() {
        if (cancelled) {
            return;
        }
        for (Account existing : service.getAccounts()) {
            if (existing.getJid().asBareJid().equals(address)) {
                service.deleteAccount(existing);
            }
        }
        account = new Account(address, password);
        service.createAccount(account);
        waitedMs = 0;
        handler.postDelayed(this::checkAccount, POLL_MS);
    }

    private void checkAccount() {
        if (cancelled) {
            return;
        }
        Account.State state = account.getStatus();
        if (state == Account.State.ONLINE) {
            cancelled = true;
            context.unbindService(connection);
            callback.onSignedIn(CogramGateway.getInstance(currentAccount).completeSignIn());
            return;
        }
        waitedMs += POLL_MS;
        if (state == Account.State.UNAUTHORIZED) {
            cancel();
            callback.onWrongPassword();
        } else if (state.isError() || waitedMs >= TIMEOUT_MS) {
            cancel();
            callback.onFailed(state.toString());
        } else {
            handler.postDelayed(this::checkAccount, POLL_MS);
        }
    }

    private void removeAccount() {
        if (service != null && account != null) {
            service.deleteAccount(account);
            account = null;
        }
    }
}
