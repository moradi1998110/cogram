package org.telegram.cogram.drawings;

import android.content.Context;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.cogram.measure.MeasureBar;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Collections;
import java.util.Iterator;

/**
 * A kept drawing, opened from the map or the list: what it measures, its layer, note and own fields, then what can be
 * done with it: edit its details or its shape, show it, share it as a file, delete it.
 */
public final class DrawingSheet extends BottomSheet {

    public interface Actions {
        /** Puts the drawing's points back on the map to change them, or moves a landmark. */
        void editShape(Drawing drawing);

        void showOnMap(Drawing drawing);
    }

    public static void show(Context context, Drawing drawing, Actions actions) {
        new DrawingSheet(context, drawing, actions).show();
    }

    /** What a drawing is and measures: "Area · 2 dunams 3 olks", "Line · 350 m", a landmark's coordinates. */
    public static String describe(Drawing drawing) {
        final double[] measure = drawing.measure();
        switch (drawing.kind) {
            case Drawing.AREA:
                return LocaleController.getString(R.string.PhogramDrawingArea) + " · " + MeasureBar.area(measure[1])
                        + " · " + LocaleController.formatString(R.string.PhogramDrawingPerimeter, MeasureBar.length(measure[0]));
            case Drawing.LINE:
                return LocaleController.getString(R.string.PhogramDrawingLine) + " · " + MeasureBar.length(measure[0]);
            default:
                return LocaleController.getString(R.string.PhogramDrawingPoint) + " · "
                        + MeasureBar.ltr(MeasureBar.coordinates(drawing.coordinates[0], drawing.coordinates[1]));
        }
    }

    /** The drawing's name, or its kind's when it has none. */
    public static String title(Drawing drawing) {
        if (drawing.name != null && !drawing.name.isEmpty()) return drawing.name;
        return LocaleController.getString(drawing.kind == Drawing.AREA ? R.string.PhogramDrawingArea
                : drawing.kind == Drawing.LINE ? R.string.PhogramDrawingLine : R.string.PhogramDrawingPoint);
    }

    private DrawingSheet(Context context, Drawing drawing, Actions actions) {
        super(context, false);
        setTitle(title(drawing), true);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        final TextView about = new TextView(context);
        about.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        about.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        String text = describe(drawing);
        for (DrawingsStore.Layer layer : DrawingsStore.get().layers()) {
            if (layer.id == drawing.layerId) text += "\n" + LocaleController.formatString(R.string.PhogramDrawingInLayer, layer.name);
        }
        if (drawing.note != null && !drawing.note.isEmpty()) text += "\n\n" + drawing.note;
        about.setText(text);
        content.addView(about, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 0, 21, 8));

        // The fields an imported file brought, or the user's own.
        final Iterator<String> keys = drawing.properties.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final TextSettingsCell cell = new TextSettingsCell(context);
            cell.setTextAndValue(key, String.valueOf(drawing.properties.opt(key)), false);
            content.addView(cell);
        }

        action(content, R.drawable.msg_edit, R.string.PhogramDrawingEdit, false, () -> SaveDrawingSheet.show(context, drawing, null));
        action(content, drawing.kind == Drawing.POINT ? R.drawable.msg_location : R.drawable.phogram_measure_area,
                drawing.kind == Drawing.POINT ? R.string.PhogramDrawingMove : R.string.PhogramDrawingEditShape, false, () -> actions.editShape(drawing));
        action(content, R.drawable.msg_map, R.string.PhogramDrawingShow, false, () -> actions.showOnMap(drawing));
        final TextCell share = new TextCell(context);
        share.setTextAndIcon(LocaleController.getString(R.string.PhogramDrawingsExport), R.drawable.msg_share, false);
        share.setBackground(Theme.getSelectorDrawable(false));
        share.setOnClickListener(v -> DrawingsExport.ask(getContainer(), share, Collections.singletonList(drawing), title(drawing)));
        content.addView(share);
        action(content, R.drawable.msg_delete, R.string.Delete, true, () -> new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.PhogramDrawingDelete))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> DrawingsStore.get().delete(drawing.id))
                .show());

        final ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        setCustomView(scroll);
    }

    /** A row of Telegram's action cells; it closes the sheet before acting. */
    private void action(LinearLayout content, int icon, int text, boolean red, Runnable run) {
        final TextCell cell = new TextCell(getContext());
        cell.setTextAndIcon(LocaleController.getString(text), icon, false);
        if (red) cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(v -> {
            dismiss();
            run.run();
        });
        content.addView(cell);
    }
}
