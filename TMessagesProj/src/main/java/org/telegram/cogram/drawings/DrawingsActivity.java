package org.telegram.cogram.drawings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import org.telegram.cogram.measure.MeasureBar;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Maps tab's ⋮ > My drawings: the layers, each shown or hidden on the map with a tap, and their drawings; a tap on
 * a drawing shows it on the map. Holding a row offers renaming, moving, sharing as a file and deleting; the bar
 * searches by name and imports GeoJSON, KML or GPX files as a new layer.
 */
public class DrawingsActivity extends UniversalFragment {

    private static final int IMPORT = 801;
    private static final int MENU_IMPORT = 1;

    private final DrawingSheet.Actions actions;
    private final Runnable changed = () -> {
        if (listView != null) listView.adapter.update(true);
    };
    private String query = "";

    public DrawingsActivity(DrawingSheet.Actions actions) {
        this.actions = actions;
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        actionBar.createMenu().addItem(0, R.drawable.ic_ab_search).setIsSearchField(true)
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
        actionBar.createMenu().addItem(MENU_IMPORT, R.drawable.msg_download).setContentDescription(LocaleController.getString(R.string.PhogramDrawingsImport));
        actionBar.setActionBarMenuOnItemClick(new org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_IMPORT) {
                    final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    try {
                        startActivityForResult(intent, IMPORT);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        });
        DrawingsStore.get().addListener(changed);
        return view;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        DrawingsStore.get().removeListener(changed);
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PhogramDrawingsTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        int id = 0;
        for (DrawingsStore.Layer layer : DrawingsStore.get().layers()) {
            final List<Drawing> drawings = DrawingsStore.get().drawings(layer.id);
            final UItem row = UItem.asButton(++id, layer.visible ? R.drawable.msg_views : R.drawable.msg_archive_hide, layer.name, String.valueOf(drawings.size()));
            row.object = layer;
            items.add(row);
            for (Drawing drawing : drawings) {
                if (!query.isEmpty() && !DrawingSheet.title(drawing).toLowerCase(Locale.getDefault()).contains(query)) continue;
                final UItem item = UItem.asButton(++id, icon(drawing.kind), DrawingSheet.title(drawing), shortMeasure(drawing));
                item.object = drawing;
                items.add(item);
            }
            items.add(UItem.asShadow(null));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.PhogramDrawingsHint)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof DrawingsStore.Layer) {
            final DrawingsStore.Layer layer = (DrawingsStore.Layer) item.object;
            DrawingsStore.get().setLayerVisible(layer.id, !layer.visible);
        } else if (item.object instanceof Drawing) {
            finishFragment();
            actions.showOnMap((Drawing) item.object);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        final ViewGroup container = (ViewGroup) fragmentView;
        if (item.object instanceof DrawingsStore.Layer) {
            final DrawingsStore.Layer layer = (DrawingsStore.Layer) item.object;
            ItemOptions.makeOptions(container, null, view)
                    .add(R.drawable.msg_edit, LocaleController.getString(R.string.Rename), () -> SaveDrawingSheet.askLayerName(getParentActivity(), layer, l -> {}))
                    .add(R.drawable.msg_share, LocaleController.getString(R.string.PhogramDrawingsExport), () -> DrawingsExport.ask(container, view, DrawingsStore.get().drawings(layer.id), layer.name))
                    .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDelete(
                            LocaleController.formatString(R.string.PhogramDrawingDeleteLayer, layer.name), () -> DrawingsStore.get().deleteLayer(layer.id)))
                    .show();
            return true;
        }
        if (item.object instanceof Drawing) {
            final Drawing drawing = (Drawing) item.object;
            ItemOptions.makeOptions(container, null, view)
                    .add(R.drawable.msg_edit, LocaleController.getString(R.string.PhogramDrawingEdit), () -> SaveDrawingSheet.show(getParentActivity(), drawing, null))
                    .add(R.drawable.msg_share, LocaleController.getString(R.string.PhogramDrawingsExport), () -> DrawingsExport.ask(container, view, java.util.Collections.singletonList(drawing), DrawingSheet.title(drawing)))
                    .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDelete(
                            LocaleController.getString(R.string.PhogramDrawingDelete), () -> DrawingsStore.get().delete(drawing.id)))
                    .show();
            return true;
        }
        return false;
    }

    private void confirmDelete(String title, Runnable delete) {
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> delete.run())
                .create());
    }

    /** A file picked to import: its drawings in a new layer named after it. */
    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != IMPORT || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        String fileName = "drawings.geojson";
        try (Cursor cursor = getParentActivity().getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) fileName = cursor.getString(0);
        } catch (Exception e) {
            FileLog.e(e);
        }
        try (InputStream in = getParentActivity().getContentResolver().openInputStream(uri)) {
            final List<Drawing> drawings = DrawingFormats.read(in, fileName, Theme.getColor(Theme.key_color_blue));
            if (drawings.isEmpty()) throw new IllegalStateException("nothing to import");
            final int dot = fileName.lastIndexOf('.');
            final long layer = DrawingsStore.get().addLayer(dot > 0 ? fileName.substring(0, dot) : fileName);
            for (Drawing drawing : drawings) drawing.layerId = layer;
            DrawingsStore.get().saveAll(drawings);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.PhogramDrawingsImported, drawings.size())).show();
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.PhogramDrawingsImportFailed)).show();
        }
    }

    static int icon(int kind) {
        return kind == Drawing.AREA ? R.drawable.phogram_measure_area : kind == Drawing.LINE ? R.drawable.phogram_measure_distance : R.drawable.msg_location;
    }

    private static String shortMeasure(Drawing drawing) {
        final double[] measure = drawing.measure();
        if (drawing.kind == Drawing.AREA) return MeasureBar.area(measure[1]);
        if (drawing.kind == Drawing.LINE) return MeasureBar.length(measure[0]);
        return "";
    }
}
