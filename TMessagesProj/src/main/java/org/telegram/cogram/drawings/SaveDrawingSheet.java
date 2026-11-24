package org.telegram.cogram.drawings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.cogram.DialogField;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.List;

/**
 * The sheet that keeps a drawing, new or kept already: its measure, then its name, note, layer and color, in
 * Telegram's fields, cells and colors.
 */
public final class SaveDrawingSheet extends BottomSheet {

    private final Drawing drawing;
    private final Runnable onSaved;
    private final EditTextBoldCursor name;
    private final EditTextBoldCursor note;
    private final TextSettingsCell layerCell;
    private final LinearLayout swatches;

    public static void show(Context context, Drawing drawing, Runnable onSaved) {
        new SaveDrawingSheet(context, drawing, onSaved).show();
    }

    private SaveDrawingSheet(Context context, Drawing drawing, Runnable onSaved) {
        super(context, true);
        this.drawing = drawing;
        this.onSaved = onSaved;
        final boolean isNew = DrawingsStore.get().find(drawing.id) == null;
        setTitle(LocaleController.getString(isNew ? R.string.PhogramDrawingSave : R.string.PhogramDrawingEdit), true);
        if (drawing.layerId == 0) drawing.layerId = DrawingsStore.get().defaultLayer();

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        final TextView measure = new TextView(context);
        measure.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        measure.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        measure.setText(DrawingSheet.describe(drawing));
        content.addView(measure, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 0, 21, 8));

        name = DialogField.create(context, R.string.PhogramDrawingName, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(drawing.name);
        content.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 0, 21, 8));
        note = DialogField.create(context, R.string.PhogramDrawingNote, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setSingleLine(false);
        note.setMaxLines(4);
        note.setText(drawing.note);
        content.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 0, 21, 8));

        layerCell = new TextSettingsCell(context);
        layerCell.setBackground(Theme.getSelectorDrawable(false));
        layerCell.setOnClickListener(v -> chooseLayer());
        content.addView(layerCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateLayer();

        swatches = new LinearLayout(context);
        for (int key : Theme.keys_colors) {
            final int color = Theme.getColor(key);
            final View swatch = new Swatch(context, color);
            swatch.setOnClickListener(v -> {
                drawing.color = color;
                for (int i = 0; i < swatches.getChildCount(); i++) swatches.getChildAt(i).invalidate();
            });
            swatches.addView(swatch, LayoutHelper.createLinear(0, 40, 1f));
        }
        content.addView(swatches, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 16, 8, 16, 8));

        final ButtonWithCounterView save = new ButtonWithCounterView(context, null);
        save.setText(LocaleController.getString(R.string.Save), false);
        save.setOnClickListener(v -> save());
        content.addView(save, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 8, 16, 16));
        setCustomView(content);
    }

    private void save() {
        drawing.name = name.getText().toString().trim();
        drawing.note = note.getText().toString().trim();
        drawing.updated = System.currentTimeMillis();
        DrawingsStore.get().save(drawing);
        dismiss();
        if (onSaved != null) onSaved.run();
    }

    private void updateLayer() {
        String layerName = "";
        for (DrawingsStore.Layer layer : DrawingsStore.get().layers()) {
            if (layer.id == drawing.layerId) layerName = layer.name;
        }
        layerCell.setTextAndValue(LocaleController.getString(R.string.PhogramDrawingLayer), layerName, false);
    }

    private void chooseLayer() {
        final ItemOptions options = ItemOptions.makeOptions(getContainer(), null, layerCell);
        final List<DrawingsStore.Layer> layers = DrawingsStore.get().layers();
        for (DrawingsStore.Layer layer : layers) {
            options.add(R.drawable.msg_folders, layer.name, () -> {
                drawing.layerId = layer.id;
                updateLayer();
            });
        }
        options.add(R.drawable.msg_addfolder, LocaleController.getString(R.string.PhogramDrawingNewLayer), () -> askLayerName(getContext(), null, id -> {
            drawing.layerId = id;
            updateLayer();
        }));
        options.show();
    }

    public interface LayerCallback {
        void onLayer(long id);
    }

    /** A new layer's name, or {@code layer}'s new one; the callback has the layer's id. */
    public static void askLayerName(Context context, DrawingsStore.Layer layer, LayerCallback callback) {
        final EditTextBoldCursor field = DialogField.create(context, R.string.PhogramDrawingLayerName, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (layer != null) field.setText(layer.name);
        final LinearLayout frame = new LinearLayout(context);
        frame.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 8));
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(layer == null ? R.string.PhogramDrawingNewLayer : R.string.Rename))
                .setView(frame)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
                    final String text = field.getText().toString().trim();
                    if (text.isEmpty()) return;
                    if (layer == null) {
                        callback.onLayer(DrawingsStore.get().addLayer(text));
                    } else {
                        DrawingsStore.get().renameLayer(layer.id, text);
                        callback.onLayer(layer.id);
                    }
                })
                .show();
    }

    /** One of Telegram's colors, ringed when it is the drawing's. */
    private final class Swatch extends View {
        private final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Swatch(Context context, int color) {
            super(context);
            this.color = color;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(cx, cy, dp(13), paint);
            if (drawing.color == color) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                canvas.drawCircle(cx, cy, dp(17), paint);
            }
        }
    }
}
