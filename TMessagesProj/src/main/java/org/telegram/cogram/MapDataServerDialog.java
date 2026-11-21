package org.telegram.cogram;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Settings > Data and Storage > Map server: where the app downloads its map files. Empty means Phogram's public
 * map data on the internet; an organization enters its own server, which serves the same catalog.json and files.
 */
public final class MapDataServerDialog {

    private MapDataServerDialog() {
    }

    public static void show(BaseFragment fragment, Runnable onChanged) {
        final Context context = fragment.getParentActivity();
        if (context == null) return;

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        final TextView info = new TextView(context);
        info.setText(LocaleController.getString(R.string.PhogramMapsServerInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        layout.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 12));

        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setHint(LocaleController.getString(R.string.PhogramMapsServerHint));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setBackground(null);
        field.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated),
                Theme.getColor(Theme.key_text_RedBold));
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        field.setText(MapData.getCustomServer());
        // Addresses read left to right in every language.
        field.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
        final FrameLayout fieldContainer = new FrameLayout(context);
        fieldContainer.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        layout.addView(fieldContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.PhogramMapsServer));
        builder.setView(layout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setNeutralButton(LocaleController.getString(R.string.PhogramMapsServerReset), (dialog, which) -> {
            MapData.setCustomServer("");
            onChanged.run();
        });
        builder.setPositiveButton(LocaleController.getString(R.string.Save), null);
        final AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String address = field.getText().toString().trim();
            if (!MapData.isValidServer(address)) {
                AndroidUtilities.shakeViewSpring(field, -6);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error,
                        LocaleController.getString(R.string.PhogramMapsServerInvalid)).show();
                return;
            }
            MapData.setCustomServer(address);
            onChanged.run();
            dialog.dismiss();
        });
    }
}
