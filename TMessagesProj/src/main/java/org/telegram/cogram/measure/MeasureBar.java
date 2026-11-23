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
 * A tap on an area changes its unit: dunams (Iraq's, 2500 m²), square meters, hectares.
 */
public final class MeasureBar extends LinearLayout {

    public interface Listener {
        void onAdd();

        void onUndo();

        void onClose();
    }

    private static final int DUNAMS = 0;
    private static final int SQUARE_METERS = 1;
    private static final int HECTARES = 2;
    /** The area's unit, kept while the app runs. */
    private static int areaUnit = DUNAMS;

    private final ImageView close;
    private final ImageView undo;
    private final ImageView add;
    private final TextView value;
    private final TextView detail;

    private double[] shown;

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
        texts.setOnClickListener(v -> nextUnit());
        addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        undo = button(context, R.drawable.photo_undo2, R.string.Undo);
        undo.setOnClickListener(v -> listener.onUndo());
        addView(undo, LayoutHelper.createLinear(48, 48));

        add = button(context, R.drawable.msg_add, R.string.PhogramMeasureAddPoint);
        add.setOnClickListener(v -> listener.onAdd());
        addView(add, LayoutHelper.createLinear(40, 40, 0, 0, 4, 0));

        updateColors();
    }

    /** What {@code measurement} measures with the point under the map's center: {@link Measurement#measure}. */
    public void set(Measurement measurement, double[] result) {
        shown = measurement.isEmpty() ? null : result;
        undo.setEnabled(!measurement.isEmpty());
        undo.setAlpha(measurement.isEmpty() ? 0.4f : 1f);
        if (shown == null) {
            value.setText(LocaleController.getString(R.string.PhogramMeasureHint));
            detail.setVisibility(GONE);
        } else if (measurement.area) {
            value.setText(area(result[1]));
            detail.setText(LocaleController.formatString(R.string.PhogramMeasurePerimeter, length(result[0])));
            detail.setVisibility(VISIBLE);
        } else {
            value.setText(length(result[0]));
            detail.setVisibility(GONE);
        }
        value.setTextColor(Theme.getColor(shown == null ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_windowBackgroundWhiteBlackText));
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
        value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        detail.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
    }

    private void nextUnit() {
        if (shown == null || detail.getVisibility() != VISIBLE) return;
        areaUnit = (areaUnit + 1) % 3;
        value.setText(area(shown[1]));
    }

    private static String length(double meters) {
        if (meters < 1000) {
            return LocaleController.formatString(R.string.PhogramMapsMeters, (int) Math.round(meters));
        }
        return LocaleController.formatString(R.string.PhogramUnitKilometers, String.format(Locale.US, "%.2f", meters / 1000));
    }

    private static String area(double squareMeters) {
        switch (areaUnit) {
            case SQUARE_METERS:
                return LocaleController.formatString(R.string.PhogramUnitSquareMeters, String.format(Locale.US, "%,d", Math.round(squareMeters)));
            case HECTARES:
                return LocaleController.formatString(R.string.PhogramUnitHectares, String.format(Locale.US, "%.2f", squareMeters / 10000));
            default:
                return LocaleController.formatString(R.string.PhogramUnitDunams, String.format(Locale.US, "%.2f", squareMeters / 2500));
        }
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
