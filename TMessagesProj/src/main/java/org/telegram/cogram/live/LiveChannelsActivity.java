package org.telegram.cogram.live;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.cogram.DialogField;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
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
                LocaleController.getString(R.string.Rename),
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
        final EditTextBoldCursor nameField = DialogField.create(context, R.string.PhogramLiveChannelName, InputType.TYPE_CLASS_TEXT);
        layout.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 8));
        EditTextBoldCursor linkField = null;
        if (channel == null) {
            linkField = DialogField.create(context, R.string.PhogramLiveChannelLink, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            // Links read left to right in every language.
            linkField.setTextDirection(View.TEXT_DIRECTION_LTR);
            layout.addView(linkField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        } else {
            nameField.setText(channel.name);
        }
        final EditTextBoldCursor link = linkField;

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(channel == null ? R.string.PhogramLiveAddChannel : R.string.Rename));
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
            if (channel != null) {
                save(channel.id, name, dialog);
                return;
            }
            final String input = link.getText().toString();
            final String id = LiveChannels.parseId(input);
            if (id != null) {
                add(id, name, link, dialog);
                return;
            }
            final String handle = LiveStreamResolver.parseHandle(input);
            if (handle == null) {
                rejectLink(link);
                return;
            }
            // A handle link, as YouTube's Share gives: ask YouTube which channel it is.
            v.setEnabled(false);
            LiveStreamResolver.resolveHandle(handle, channelId -> {
                v.setEnabled(true);
                if (channelId == null) {
                    rejectLink(link);
                } else {
                    add(channelId, name, link, dialog);
                }
            });
        });
    }

    private void add(String id, String name, View link, AlertDialog dialog) {
        if (LiveChannels.contains(id)) {
            AndroidUtilities.shakeViewSpring(link, -6);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.PhogramLiveAlreadyAdded)).show();
            return;
        }
        save(id, name, dialog);
    }

    private void save(String id, String name, AlertDialog dialog) {
        LiveChannels.put(id, name);
        listView.adapter.update(true);
        dialog.dismiss();
    }

    private void rejectLink(View link) {
        AndroidUtilities.shakeViewSpring(link, -6);
        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.PhogramLiveInvalidLink), 3).show();
    }
}
