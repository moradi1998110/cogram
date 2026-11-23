package org.telegram.cogram;

import android.content.Context;
import android.util.TypedValue;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;

/** A one-line text field for Phogram's dialogs, styled like Telegram's own dialog fields. */
public final class DialogField {

    private DialogField() {
    }

    public static EditTextBoldCursor create(Context context, int hint, int inputType) {
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setHint(LocaleController.getString(hint));
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setBackground(null);
        field.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated),
                Theme.getColor(Theme.key_text_RedBold));
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        return field;
    }
}
