package org.telegram.cogram.live;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

/** Services > Live TV: the channels to watch, and the ones this user adds or removes. */
public class LiveChannelsActivity extends UniversalFragment {

    private static final int ID_ADD = 1;
    /** Channel rows use ids from here up, by their place in the list; other rows keep clear of them. */
    private static final int ID_CHANNEL = 100;

    private List<LiveChannel> channels = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PhogramServiceLiveTv);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        channels = LiveChannels.all();
        for (int i = 0; i < channels.size(); i++) {
            items.add(UItem.asButton(ID_CHANNEL + i, R.drawable.msg_videocall, channels.get(i).name));
        }
        items.add(UItem.asButton(ID_ADD, R.drawable.msg_add, LocaleController.getString(R.string.PhogramLiveAddChannel)).accent());
        items.add(UItem.asShadow(LocaleController.getString(R.string.PhogramLiveAddHint)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ADD) {
            showEditor(null);
        } else {
            final LiveChannel channel = channelOf(item);
            if (channel != null) presentFragment(new LiveTvActivity(channel));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        final LiveChannel channel = channelOf(item);
        if (channel == null || channel.builtIn) return false;
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(channel.name);
        builder.setItems(new CharSequence[]{
                LocaleController.getString(R.string.PhogramLiveRename),
                LocaleController.getString(R.string.Delete)
        }, (dialog, which) -> {
            if (which == 0) {
                showEditor(channel);
            } else {
                LiveChannels.remove(channel.id);
                listView.adapter.update(true);
            }
        });
        showDialog(builder.create());
        return true;
    }

    private LiveChannel channelOf(UItem item) {
        final int index = item.id - ID_CHANNEL;
        return index >= 0 && index < channels.size() ? channels.get(index) : null;
    }

    /** Adds a channel, or renames {@code channel} when it is given. */
    private void showEditor(LiveChannel channel) {
        final Context context = getParentActivity();
        if (context == null) return;

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final EditTextBoldCursor nameField = field(context, R.string.PhogramLiveChannelName, InputType.TYPE_CLASS_TEXT);
        layout.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 8));
        EditTextBoldCursor linkField = null;
        if (channel == null) {
            linkField = field(context, R.string.PhogramLiveChannelLink, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            // Links read left to right in every language.
            linkField.setTextDirection(View.TEXT_DIRECTION_LTR);
            layout.addView(linkField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        } else {
            nameField.setText(channel.name);
        }
        final EditTextBoldCursor link = linkField;

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(channel == null ? R.string.PhogramLiveAddChannel : R.string.PhogramLiveRename));
        builder.setView(layout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), null);
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String name = nameField.getText().toString().trim();
            if (name.isEmpty()) {
                AndroidUtilities.shakeViewSpring(nameField, -6);
                return;
            }
            final String id = channel != null ? channel.id : LiveChannels.parseId(link.getText().toString());
            if (id == null) {
                AndroidUtilities.shakeViewSpring(link, -6);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.PhogramLiveInvalidLink), 3).show();
                return;
            }
            if (channel == null && LiveChannels.contains(id)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.PhogramLiveAlreadyAdded)).show();
                return;
            }
            LiveChannels.put(id, name);
            listView.adapter.update(true);
            dialog.dismiss();
        });
    }

    private static EditTextBoldCursor field(Context context, int hint, int inputType) {
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
