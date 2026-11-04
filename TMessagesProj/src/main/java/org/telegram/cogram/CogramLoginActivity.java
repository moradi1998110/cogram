package org.telegram.cogram;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.LaunchActivity;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;

/** Temporary sign-in with an XMPP address and password, until Telegram's login screen is adapted. */
public class CogramLoginActivity extends Activity {

    public static final String EXTRA_DEMO = "demo";

    private static final int POLL_MS = 1000;
    private static final int TIMEOUT_MS = 60000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText address;
    private EditText password;
    private Button signIn;
    private TextView status;
    private XmppConnectionService service;
    private Account account;
    private int waitedMs;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra(EXTRA_DEMO, false)) {
            openDemo();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setFitsSystemWindows(true);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);

        address = new EditText(this);
        address.setHint("user@example.org");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        address.setTextDirection(TextView.TEXT_DIRECTION_LTR);
        root.addView(address);

        password = new EditText(this);
        password.setHint("كلمة المرور");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password);

        signIn = new Button(this);
        signIn.setText("تسجيل الدخول");
        signIn.setOnClickListener(view -> onSignIn());
        root.addView(signIn);

        Button demo = new Button(this);
        demo.setText("بيانات تجريبية");
        demo.setOnClickListener(view -> openDemo());
        root.addView(demo);

        status = new TextView(this);
        root.addView(status);
        setContentView(root);
    }

    private void openDemo() {
        CogramGateway.selectDemo();
        openApp();
    }

    private void openApp() {
        CogramGateway.getInstance(0).onSignedIn();
        startActivity(new Intent(this, LaunchActivity.class));
        finish();
    }

    private void onSignIn() {
        Jid jid;
        try {
            jid = Jid.ofUserInput(address.getText().toString().trim());
        } catch (IllegalArgumentException e) {
            status.setText("العنوان غير صالح");
            return;
        }
        if (jid.getLocal() == null || password.length() == 0) {
            status.setText("أدخل العنوان وكلمة المرور");
            return;
        }
        signIn.setEnabled(false);
        status.setText("جارٍ الاتصال...");
        if (service != null) {
            createAccount();
            return;
        }
        Intent intent = new Intent(this, XmppConnectionService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void createAccount() {
        Jid jid = Jid.ofUserInput(address.getText().toString().trim());
        account = new Account(jid.asBareJid(), password.getText().toString());
        service.createAccount(account);
        waitedMs = 0;
        handler.postDelayed(this::checkAccount, POLL_MS);
    }

    private void checkAccount() {
        Account.State state = account.getStatus();
        if (state == Account.State.ONLINE) {
            openApp();
            return;
        }
        waitedMs += POLL_MS;
        boolean failed = state.isError() || waitedMs >= TIMEOUT_MS;
        if (failed) {
            status.setText("تعذّر الدخول: " + state);
            service.deleteAccount(account);
            account = null;
            signIn.setEnabled(true);
            return;
        }
        status.setText("جارٍ الاتصال: " + state);
        handler.postDelayed(this::checkAccount, POLL_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (service != null) {
            unbindService(connection);
        }
        super.onDestroy();
    }
}
