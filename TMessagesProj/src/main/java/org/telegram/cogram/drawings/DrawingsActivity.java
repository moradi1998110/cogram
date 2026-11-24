package org.telegram.cogram.drawings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The Maps tab's layers > My drawings, as a file manager shows a folder: the layers as folders on top, then the
 * drawings kept in My drawings itself (the first layer, which the list does not show as one). A tap opens a folder or
 * a drawing; each row has its kind, an eye that shows or hides it on the map and ⋮ for its actions. Chips filter by
 * kind, the bar searches by name. Holding a row picks drawings to share, move or delete together, as Telegram picks
 * messages; sharing from the bar asks the format first, then which drawings. A file imported opens this page again
 * with its drawings, all picked, to keep the ones wanted in a new folder or one there is.
 */
public class DrawingsActivity extends UniversalFragment {

    private static final int IMPORT = 801;
    private static final int MENU_IMPORT = 1, MENU_EXPORT = 2, SELECT_ALL = 3, MOVE = 4, DELETE = 5, SHARE = 6, DONE = 7;
    private static final int LAYER_ROW = 1;

    private final DrawingSheet.Actions actions;
    private final Runnable changed = () -> {
        if (listView != null) listView.adapter.update(true);
    };
    private KindFilter filter;
    private String query = "";

    /** Picking: the drawings' ids, and the format asked first when sharing from the bar. */
    private final HashSet<String> selected = new HashSet<>();
    private boolean selecting;
    private int exportFormat = -1;
    private NumberTextView count;
    private ActionBarMenuItem exportItem, moveItem, deleteItem, shareItem;

    /** A folder's page: its layer, and My drawings' page under it; or none on My drawings itself. */
    private final DrawingsStore.Layer folder;
    private final DrawingsActivity parent;

    /** Importing: the file's drawings, its name, and the layer they go to, 0 for a new one named after the file. */
    private final List<Drawing> imported;
    private final String importName;
    private long importLayer;

    public DrawingsActivity(DrawingSheet.Actions actions) {
        this.actions = actions;
        folder = null;
        parent = null;
        imported = null;
        importName = null;
    }

    private DrawingsActivity(DrawingsActivity parent, DrawingsStore.Layer folder) {
        actions = parent.actions;
        this.folder = folder;
        this.parent = parent;
        imported = null;
        importName = null;
    }

    private DrawingsActivity(DrawingsActivity parent, List<Drawing> drawings, String name) {
        actions = parent.actions;
        folder = null;
        this.parent = parent;
        imported = drawings;
        importName = name;
        for (Drawing drawing : drawings) selected.add(drawing.id);
        selecting = true;
    }

    /** My drawings' own layer: the first, made when there is none. */
    private static long rootLayer() {
        return DrawingsStore.get().defaultLayer();
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        if (imported == null) {
            final ActionBarMenu menu = actionBar.createMenu();
            menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true)
                    .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                        @Override
                        public void onTextChanged(EditText editText) {
                            query = editText.getText().toString().trim().toLowerCase(Locale.getDefault());
                            listView.adapter.update(true);
                        }

                        @Override
                        public void onSearchCollapse() {
                            query = "";
                            listView.adapter.update(true);
                        }
                    });
            exportItem = menu.addItem(MENU_EXPORT, R.drawable.msg_shareout);
            exportItem.setContentDescription(LocaleController.getString(R.string.PhogramDrawingsExport));
            menu.addItem(MENU_IMPORT, R.drawable.msg_download).setContentDescription(LocaleController.getString(R.string.PhogramDrawingsImport));
            DrawingsStore.get().addListener(changed);
            // My drawings has no eye of its own any more: one hidden in 0.0.45 shows again.
            final long root = rootLayer();
            for (DrawingsStore.Layer layer : DrawingsStore.get().layers()) {
                if (layer.id == root && !layer.visible) DrawingsStore.get().setLayerVisible(layer.id, true);
            }
        }

        final ActionBarMenu actionMode = actionBar.createActionMode();
        count = new NumberTextView(context);
        count.setTextSize(18);
        count.setTypeface(AndroidUtilities.bold());
        count.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        actionMode.addView(count, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0));
        actionMode.addItemWithWidth(SELECT_ALL, R.drawable.msg_select, dp(54), LocaleController.getString(R.string.SelectAll));
        if (imported == null) {
            moveItem = actionMode.addItemWithWidth(MOVE, R.drawable.msg_folders, dp(54), LocaleController.getString(R.string.PhogramDrawingsMove));
            deleteItem = actionMode.addItemWithWidth(DELETE, R.drawable.msg_delete, dp(54), LocaleController.getString(R.string.Delete));
            shareItem = actionMode.addItemWithWidth(SHARE, R.drawable.msg_share, dp(54), LocaleController.getString(R.string.PhogramDrawingsExport));
        } else {
            actionMode.addItemWithWidth(DONE, R.drawable.ic_ab_done, dp(54), LocaleController.getString(R.string.PhogramDrawingsImport));
            actionBar.showActionMode(false);
            count.setNumber(selected.size(), false);
        }

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (selecting && imported == null) {
                        endSelection();
                    } else {
                        finishFragment();
                    }
                } else if (id == MENU_IMPORT) {
                    final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    try {
                        startActivityForResult(intent, IMPORT);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                } else if (id == MENU_EXPORT) {
                    DrawingsExport.choose(ItemOptions.makeOptions(DrawingsActivity.this, exportItem), format -> {
                        exportFormat = format;
                        startSelection(shown());
                    });
                } else if (id == SELECT_ALL) {
                    final List<Drawing> all = shown();
                    if (picked(all).size() == all.size()) {
                        for (Drawing drawing : all) selected.remove(drawing.id);
                    } else {
                        for (Drawing drawing : all) selected.add(drawing.id);
                    }
                    updateSelection();
                } else if (id == MOVE) {
                    chooseLayer(moveItem, 0, layer -> {
                        DrawingsStore.get().move(new ArrayList<>(selected), layer);
                        endSelection();
                    });
                } else if (id == DELETE) {
                    confirmDelete(LocaleController.formatString(R.string.PhogramDrawingsDeleteSelected, selected.size()), () -> {
                        DrawingsStore.get().delete(new ArrayList<>(selected));
                        endSelection();
                    });
                } else if (id == SHARE) {
                    if (exportFormat >= 0) {
                        share(exportFormat);
                    } else {
                        DrawingsExport.choose(ItemOptions.makeOptions(DrawingsActivity.this, shareItem), DrawingsActivity.this::share);
                    }
                } else if (id == DONE) {
                    importSelected();
                }
            }
        });
        return view;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        DrawingsStore.get().removeListener(changed);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (selecting && imported == null) {
            if (invoked) endSelection();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    protected CharSequence getTitle() {
        return imported != null ? importName : folder != null ? folder.name : LocaleController.getString(R.string.PhogramDrawingsTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (filter == null) filter = new KindFilter(getContext(), kind -> listView.adapter.update(true));
        items.add(UItem.asCustom(filter));
        if (imported != null) {
            items.add(UItem.asButton(LAYER_ROW, R.drawable.msg_folders, LocaleController.getString(R.string.PhogramDrawingLayer), importLayerName()));
            items.add(UItem.asShadow(null));
            for (Drawing drawing : imported) {
                if (matches(drawing)) items.add(DrawingCell.Factory.of(drawing.id.hashCode(), drawing, true, selected.contains(drawing.id)));
            }
            items.add(UItem.asShadow(null));
            return;
        }
        if (folder == null) {
            // The folders, each shown while filtering only when something in it matches.
            final boolean filtering = filter.getChosen() != KindFilter.ALL || !query.isEmpty();
            final long root = rootLayer();
            boolean any = false;
            for (DrawingsStore.Layer layer : DrawingsStore.get().layers()) {
                if (layer.id == root) continue;
                final List<Drawing> drawings = shown(layer.id);
                if (filtering && drawings.isEmpty()) continue;
                items.add(DrawingCell.Factory.of((int) -layer.id, layer, selecting, selecting && !drawings.isEmpty() && picked(drawings).size() == drawings.size()));
                any = true;
            }
            if (any) items.add(UItem.asShadow(null));
        }
        final List<Drawing> drawings = shown(folder != null ? folder.id : rootLayer());
        for (Drawing drawing : drawings) {
            items.add(DrawingCell.Factory.of(drawing.id.hashCode(), drawing, selecting, selected.contains(drawing.id)));
        }
        items.add(UItem.asShadow(folder == null ? LocaleController.getString(R.string.PhogramDrawingsHint) : null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (imported != null && item.id == LAYER_ROW) {
            chooseLayer(view, R.drawable.msg_addfolder, layer -> {
                importLayer = layer;
                listView.adapter.update(true);
            });
            return;
        }
        if (!(view instanceof DrawingCell)) return;
        if (selecting) {
            toggle(item.object);
            return;
        }
        final int part = ((DrawingCell) view).partAt(x);
        if (item.object instanceof DrawingsStore.Layer) {
            final DrawingsStore.Layer layer = (DrawingsStore.Layer) item.object;
            if (part == DrawingCell.EYE) {
                DrawingsStore.get().setLayerVisible(layer.id, !layer.visible);
            } else if (part == DrawingCell.MORE) {
                layerOptions(layer, view);
            } else {
                presentFragment(new DrawingsActivity(this, layer));
            }
        } else if (item.object instanceof Drawing) {
            final Drawing drawing = (Drawing) item.object;
            if (part == DrawingCell.EYE) {
                DrawingsStore.get().setHidden(drawing.id, !drawing.hidden);
            } else if (part == DrawingCell.MORE) {
                drawingOptions(drawing, view);
            } else {
                DrawingSheet.show(getParentActivity(), drawing, leaving);
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (imported != null || !(view instanceof DrawingCell)) return false;
        if (selecting) {
            toggle(item.object);
        } else if (item.object instanceof Drawing) {
            startSelection(Collections.singletonList((Drawing) item.object));
        } else {
            startSelection(shown(((DrawingsStore.Layer) item.object).id));
        }
        return true;
    }

    /** The map's actions, this page (and My drawings under a folder's) closed first so the map shows. */
    private final DrawingSheet.Actions leaving = new DrawingSheet.Actions() {
        @Override
        public void editShape(Drawing drawing) {
            leave();
            actions.editShape(drawing);
        }

        @Override
        public void showOnMap(Drawing drawing) {
            leave();
            actions.showOnMap(drawing);
        }
    };

    private void leave() {
        if (parent != null) parent.removeSelfFromStack();
        finishFragment();
    }

    private void layerOptions(DrawingsStore.Layer layer, View view) {
        ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_edit, LocaleController.getString(R.string.Rename), () -> SaveDrawingSheet.askLayerName(getParentActivity(), layer, l -> {}))
                .add(R.drawable.msg_share, LocaleController.getString(R.string.PhogramDrawingsExport), () -> DrawingsExport.ask((ViewGroup) fragmentView, view, DrawingsStore.get().drawings(layer.id), layer.name))
                .add(R.drawable.msg_select, LocaleController.getString(R.string.Select), () -> startSelection(shown(layer.id)))
                .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDelete(
                        LocaleController.formatString(R.string.PhogramDrawingDeleteLayer, layer.name), () -> DrawingsStore.get().deleteLayer(layer.id)))
                .show();
    }

    private void drawingOptions(Drawing drawing, View view) {
        ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_map, LocaleController.getString(R.string.PhogramDrawingShow), () -> leaving.showOnMap(drawing))
                .add(R.drawable.msg_edit, LocaleController.getString(R.string.PhogramDrawingEdit), () -> SaveDrawingSheet.show(getParentActivity(), drawing, null))
                .add(R.drawable.msg_share, LocaleController.getString(R.string.PhogramDrawingsExport), () -> DrawingsExport.ask((ViewGroup) fragmentView, view, Collections.singletonList(drawing), DrawingSheet.title(drawing)))
                .add(R.drawable.msg_select, LocaleController.getString(R.string.Select), () -> startSelection(Collections.singletonList(drawing)))
                .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDelete(
                        LocaleController.getString(R.string.PhogramDrawingDelete), () -> DrawingsStore.get().delete(drawing.id)))
                .show();
    }

    /** Telegram's menu of layers, under {@code anchor}; with {@code newIcon}, a new layer (0) heads it. */
    private void chooseLayer(View anchor, int newIcon, Utilities.Callback<Long> chosen) {
        final ItemOptions options = ItemOptions.makeOptions(this, anchor);
        if (newIcon != 0) options.add(newIcon, LocaleController.formatString(R.string.PhogramDrawingsNewLayerNamed, importName), () -> chosen.run(0L));
        for (DrawingsStore.Layer layer : DrawingsStore.get().layers()) {
            options.add(R.drawable.msg_folders, layer.name, () -> chosen.run(layer.id));
        }
        options.show();
    }

    // Filtering.

    private boolean matches(Drawing drawing) {
        return (filter == null || filter.getChosen() == KindFilter.ALL || filter.getChosen() == drawing.kind)
                && (query.isEmpty() || DrawingSheet.title(drawing).toLowerCase(Locale.getDefault()).contains(query));
    }

    private List<Drawing> shown(long layerId) {
        final List<Drawing> out = new ArrayList<>();
        for (Drawing drawing : DrawingsStore.get().drawings(layerId)) {
            if (matches(drawing)) out.add(drawing);
        }
        return out;
    }

    /** Every drawing the list shows: the file's, or each layer's. */
    private List<Drawing> shown() {
        if (imported != null) {
            final List<Drawing> out = new ArrayList<>();
            for (Drawing drawing : imported) {
                if (matches(drawing)) out.add(drawing);
            }
            return out;
        }
        if (folder != null) return shown(folder.id);
        final List<Drawing> out = new ArrayList<>();
        for (DrawingsStore.Layer layer : DrawingsStore.get().layers()) out.addAll(shown(layer.id));
        return out;
    }

    private List<Drawing> picked(List<Drawing> drawings) {
        final List<Drawing> out = new ArrayList<>();
        for (Drawing drawing : drawings) {
            if (selected.contains(drawing.id)) out.add(drawing);
        }
        return out;
    }

    // Picking.

    private void startSelection(List<Drawing> drawings) {
        selecting = true;
        for (Drawing drawing : drawings) selected.add(drawing.id);
        // Sharing in a format asked first: only sharing is left to do.
        moveItem.setVisibility(exportFormat < 0 ? View.VISIBLE : View.GONE);
        deleteItem.setVisibility(exportFormat < 0 ? View.VISIBLE : View.GONE);
        actionBar.showActionMode();
        updateSelection();
    }

    private void endSelection() {
        selecting = false;
        selected.clear();
        exportFormat = -1;
        actionBar.hideActionMode();
        listView.adapter.update(true);
    }

    /** A drawing picked or let go; a layer's drawings all picked, or all let go when they were. */
    private void toggle(Object object) {
        final List<Drawing> drawings = object instanceof Drawing ? Collections.singletonList((Drawing) object) : shown(((DrawingsStore.Layer) object).id);
        final boolean all = picked(drawings).size() == drawings.size();
        for (Drawing drawing : drawings) {
            if (all) selected.remove(drawing.id);
            else selected.add(drawing.id);
        }
        if (selected.isEmpty() && imported == null && exportFormat < 0) {
            endSelection();
        } else {
            updateSelection();
        }
    }

    private void updateSelection() {
        count.setNumber(selected.size(), true);
        listView.adapter.update(true);
    }

    private void share(int format) {
        final List<Drawing> drawings = new ArrayList<>();
        for (DrawingsStore.Layer layer : DrawingsStore.get().layers()) drawings.addAll(picked(DrawingsStore.get().drawings(layer.id)));
        if (drawings.isEmpty()) return;
        DrawingsExport.share(getParentActivity(), format, drawings, LocaleController.getString(R.string.PhogramDrawingsTitle));
        endSelection();
    }

    private void confirmDelete(String title, Runnable delete) {
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> delete.run())
                .create());
    }

    // Importing.

    /** A file picked to import: its drawings shown to keep the ones wanted. */
    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != IMPORT || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        String fileName = "drawings.geojson";
        try (Cursor cursor = getParentActivity().getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) fileName = cursor.getString(0);
        } catch (Exception e) {
            FileLog.e(e);
        }
        try (InputStream in = getParentActivity().getContentResolver().openInputStream(uri)) {
            final List<Drawing> drawings = DrawingFormats.read(in, Theme.getColor(Theme.key_color_blue));
            if (drawings.isEmpty()) throw new IllegalStateException("nothing to import");
            final int dot = fileName.lastIndexOf('.');
            presentFragment(new DrawingsActivity(this, drawings, dot > 0 ? fileName.substring(0, dot) : fileName));
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.PhogramDrawingsImportFailed)).show();
        }
    }

    /** The layer chosen; a new one when it was none or is gone since. */
    private String importLayerName() {
        for (DrawingsStore.Layer layer : DrawingsStore.get().layers()) {
            if (layer.id == importLayer) return layer.name;
        }
        importLayer = 0;
        return LocaleController.formatString(R.string.PhogramDrawingsNewLayerNamed, importName);
    }

    private void importSelected() {
        final List<Drawing> drawings = picked(imported);
        if (drawings.isEmpty()) return;
        importLayerName();
        final long layer = importLayer != 0 ? importLayer : DrawingsStore.get().addLayer(importName);
        for (Drawing drawing : drawings) drawing.layerId = layer;
        DrawingsStore.get().saveAll(drawings);
        finishFragment();
        BulletinFactory.of(parent).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.PhogramDrawingsImported, drawings.size())).show();
    }
}
