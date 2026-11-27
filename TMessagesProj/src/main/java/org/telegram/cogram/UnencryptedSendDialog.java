package org.telegram.cogram;

import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

/**
 * Asks before writing unencrypted to a contact who publishes no OMEMO keys. A contact whose keys
 * were known and are now gone gets a stronger warning, since their server may be hiding them.
 */
final class UnencryptedSendDialog {

    // Several messages of one album fail together; one question answers them all.
    private static boolean showing;

    private UnencryptedSendDialog() {
    }

    static void show(int account, long userId, boolean keysGone) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (showing || fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        String name = UserObject.getFirstName(MessagesController.getInstance(account).getUser(userId));
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(keysGone ? R.string.PhogramEncryptionGoneTitle : R.string.PhogramNoEncryptionTitle));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(
                keysGone ? R.string.PhogramEncryptionGoneText : R.string.PhogramNoEncryptionText, name)));
        builder.setPositiveButton(LocaleController.getString(R.string.PhogramSendUnencrypted),
                (dialog, which) -> CogramGateway.getInstance(account).allowUnencrypted(userId));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showing = true;
        if (fragment.showDialog(dialog, d -> showing = false) == null) {
            showing = false;
            return;
        }
        View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold, fragment.getResourceProvider()));
        }
    }
}
