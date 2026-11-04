package eu.siacs.conversations.ui;

import android.app.Activity;

/** Stands in for the Conversations screen of this name, which the Telegram UI replaces. */
public class XmppActivity extends Activity {
    public eu.siacs.conversations.services.XmppConnectionService xmppConnectionService;

    public void switchToAccount(final eu.siacs.conversations.entities.Account account) {}
}
