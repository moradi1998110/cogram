package org.telegram.cogram.measure;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.Locale;

/**
 * The bar that takes the search bar's place while measuring, in the colors of Telegram's cards: what the shape
 * measures, and buttons to stop, to take the last point back and to put the next one down at the map's center.
 * An area reads as Iraq's land deeds write it, in dunams, olks and square meters (a dunam is 25 olks, an olk
 * 100 m²), then its total in square meters and its perimeter.
 */
public final class MeasureBar extends LinearLayout {

    public interface Listener {
        void onAdd();

        void onUndo();

        void onClose();

        void onSave();
    }

    private final ImageView close;
    private final ImageView undo;
    private final ImageView add;
    private final ImageView save;
    private final TextView value;
    private final TextView detail;

    public MeasureBar(Context context, Listener listener) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setTranslationZ(dp(3));

        close = button(context, R.drawable.ic_close_white, R.string.Close);
        close.setOnClickListener(v -> listener.onClose());
        addView(close, LayoutHelper.createLinear(48, 48));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);
        value = text(context, 16);
        detail = text(context, 13);
        texts.addView(value, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        texts.addView(detail, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        undo = button(context, R.drawable.photo_undo2, R.string.Undo);
        undo.setOnClickListener(v -> listener.onUndo());
        addView(undo, LayoutHelper.createLinear(48, 48));

        add = button(context, R.drawable.msg_add, R.string.PhogramMeasureAddPoint);
        add.setOnClickListener(v -> listener.onAdd());
        addView(add, LayoutHelper.createLinear(40, 40, 0, 0, 4, 0));

        save = button(context, R.drawable.ic_ab_done, R.string.Save);
        save.setOnClickListener(v -> listener.onSave());
        addView(save, LayoutHelper.createLinear(40, 40, 0, 0, 4, 0));

        updateColors();
    }

    /** What {@code measurement} measures with the point under the map's center: {@link Measurement#measure}. */
    public void set(Measurement measurement, double[] result) {
        final boolean empty = measurement.isEmpty();
        add.setVisibility(VISIBLE);
        undo.setVisibility(VISIBLE);
        enableSave(measurement.size() >= (measurement.area ? 3 : 2));
        undo.setEnabled(!measurement.isEmpty());
        undo.setAlpha(measurement.isEmpty() ? 0.4f : 1f);
        if (empty) {
            value.setText(LocaleController.getString(R.string.PhogramMeasureHint));
            detail.setVisibility(GONE);
        } else if (measurement.area) {
            value.setText(area(result[1]));
            detail.setText(LocaleController.formatString(R.string.PhogramMeasureAreaDetail, squareMeters(result[1]), length(result[0])));
            detail.setVisibility(VISIBLE);
        } else {
            value.setText(length(result[0]));
            detail.setVisibility(GONE);
        }
        value.setTextColor(Theme.getColor(empty ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_windowBackgroundWhiteBlackText));
    }

    /** Placing a landmark: the point is the one under the center, saved with ✓. */
    public void setPoint() {
        add.setVisibility(GONE);
        undo.setVisibility(GONE);
        enableSave(true);
        value.setText(LocaleController.getString(R.string.PhogramDrawingPlaceHint));
        value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        detail.setVisibility(GONE);
    }

    private void enableSave(boolean enabled) {
        save.setEnabled(enabled);
        save.setAlpha(enabled ? 1f : 0.4f);
    }

    public void updateColors() {
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(24));
        background.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        setBackground(background);
        final int icon = Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon);
        close.setColorFilter(new PorterDuffColorFilter(icon, PorterDuff.Mode.MULTIPLY));
        undo.setColorFilter(new PorterDuffColorFilter(icon, PorterDuff.Mode.MULTIPLY));
        close.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, dp(20)));
        undo.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, dp(20)));
        // Telegram's round action button: the accent, with its icon color.
        add.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(40), Theme.getColor(Theme.key_chats_actionBackground), Theme.getColor(Theme.key_chats_actionPressedBackground)));
        add.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.MULTIPLY));
        save.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(40), Theme.getColor(Theme.key_chats_actionBackground), Theme.getColor(Theme.key_chats_actionPressedBackground)));
        save.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.MULTIPLY));
        value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        detail.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
    }

    /** A point as decimal degrees, latitude first, as surveys and other apps read it. */
    public static String coordinates(double latitude, double longitude) {
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
    }

    public static String length(double meters) {
        if (meters < 1000) {
            return LocaleController.formatString(R.string.PhogramMapsMeters, (int) Math.round(meters));
        }
        return LocaleController.formatString(R.string.PhogramUnitKilometers, String.format(Locale.US, "%.2f", meters / 1000));
    }

    /** As a deed writes it: "2 دونم 3 أولك 45 م²", without the parts that are nought. */
    public static String area(double squareMeters) {
        final long total = Math.round(squareMeters);
        final long[] counts = { total / 2500, total % 2500 / 100, total % 100 };
        final int[] units = { R.string.PhogramUnitDunams, R.string.PhogramUnitOlks, R.string.PhogramUnitSquareMeters };
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            // Noughts are left out, but for an area of nought square meters.
            if (counts[i] == 0 && (i < counts.length - 1 || out.length() > 0)) continue;
            if (out.length() > 0) out.append("  ");
            out.append(LocaleController.formatString(units[i], String.valueOf(counts[i])));
        }
        return out.toString();
    }

    public static String squareMeters(double squareMeters) {
        return LocaleController.formatString(R.string.PhogramUnitSquareMeters, String.format(Locale.US, "%,d", Math.round(squareMeters)));
    }

    private static ImageView button(Context context, int icon, int description) {
        final ImageView view = new ImageView(context);
        view.setImageResource(icon);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setContentDescription(LocaleController.getString(description));
        ScaleStateListAnimator.apply(view);
        return view;
    }

    private static TextView text(Context context, int size) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        return view;
    }
}
