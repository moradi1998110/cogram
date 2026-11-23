package org.telegram.cogram.measure;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.cogram.MapEngine;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

/**
 * The Maps tab's tools, laid out as Telegram's chat header: the search bar in the middle, the page's round "my location"
 * button at the line's start and, at its end, a capsule of two buttons as the header's call and ⋮ are: the layers, and
 * the tools (⋮): the center point, measuring a distance and an area. While measuring, the measure bar takes the row's place and the line follows the center point.
 */
public final class MapTools {

    public interface Host {
        /** The row or the measuring started or ended: the page lays its bars out again. */
        void onToolsChanged();
    }

    /** The search bar's margins at the line's start (the round button) and end (the capsule). */
    public static final int START_MARGIN = 12 + 48 + 8;
    public static final int END_MARGIN = 12 + 2 * 44 + 8;

    private static final String KEY_CENTER = "center_shown";
    private static final String KEY_PLACES = "places_shown";

    private final BaseFragment fragment;
    private final Host host;
    private final LinearLayout capsule;
    private final ImageView layersButton;
    private final ImageView toolsButton;
    private final View center;
    private final MeasureBar measureBar;

    private MapEngine engine;
    private Measurement measurement;
    private boolean rowShown;

    public MapTools(BaseFragment fragment, FrameLayout container, Host host) {
        this.fragment = fragment;
        this.host = host;
        final Context context = container.getContext();
        capsule = new LinearLayout(context);
        capsule.setTranslationZ(dp(3));
        capsule.setVisibility(View.GONE);
        layersButton = button(context, R.drawable.msg_map_type, R.string.PhogramMapsLayers);
        layersButton.setOnClickListener(v -> openLayers());
        toolsButton = button(context, R.drawable.ic_ab_other, R.string.AccDescrMoreOptions);
        toolsButton.setOnClickListener(v -> openTools());
        // ⋮ at the capsule's outer end, as in the chat header, in both directions.
        capsule.addView(LocaleController.isRTL ? toolsButton : layersButton, LayoutHelper.createLinear(44, 48));
        capsule.addView(LocaleController.isRTL ? layersButton : toolsButton, LayoutHelper.createLinear(44, 48));
        container.addView(capsule, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48,
                Gravity.BOTTOM | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 12, 0, 12, 0));

        center = new CenterPoint(context);
        center.setVisibility(preferences().getBoolean(KEY_CENTER, false) ? View.VISIBLE : View.GONE);
        container.addView(center, LayoutHelper.createFrame(28, 28, Gravity.CENTER));

        measureBar = new MeasureBar(context, new MeasureBar.Listener() {
            @Override
            public void onAdd() {
                final double[] point = centerPoint();
                if (point == null) return;
                measurement.add(point[0], point[1]);
                onCameraMoved();
            }

            @Override
            public void onUndo() {
                measurement.undo();
                onCameraMoved();
            }

            @Override
            public void onClose() {
                close();
            }
        });
        measureBar.setVisibility(View.GONE);
        container.addView(measureBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 12, 0, 12, 0));
        updateColors();
    }

    public void setEngine(MapEngine engine) {
        this.engine = engine;
        if (engine != null) engine.setPlacesShown(preferences().getBoolean(KEY_PLACES, true));
    }

    /** The capsule shows with the search bar beside it. */
    public void setRowShown(boolean shown) {
        rowShown = shown;
        capsule.setVisibility(shown && !isMeasuring() ? View.VISIBLE : View.GONE);
    }

    public boolean isMeasuring() {
        return measurement != null;
    }

    /** Ends the measuring; false when there was none. */
    public boolean close() {
        if (measurement == null) return false;
        measurement = null;
        if (engine != null) engine.showMeasure(null, false);
        measureBar.setVisibility(View.GONE);
        center.setVisibility(preferences().getBoolean(KEY_CENTER, false) ? View.VISIBLE : View.GONE);
        host.onToolsChanged();
        return true;
    }

    /** The row's distance from the page's bottom, and where the middle of the map's free part is. */
    public void layout(int bottomMargin, float centerOffsetY) {
        ((FrameLayout.LayoutParams) capsule.getLayoutParams()).bottomMargin = bottomMargin;
        ((FrameLayout.LayoutParams) measureBar.getLayoutParams()).bottomMargin = bottomMargin;
        center.setTranslationY(centerOffsetY);
    }

    /** The map moved: the line follows the center point to where the next point would go. */
    public void onCameraMoved() {
        if (measurement == null || engine == null) return;
        final double[] cursor = centerPoint();
        engine.showMeasure(measurement.coordinates(cursor), measurement.area);
        measureBar.set(measurement, measurement.measure(cursor));
    }

    public void updateColors() {
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(24));
        background.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        capsule.setBackground(background);
        for (ImageView button : new ImageView[] { layersButton, toolsButton }) {
            button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, dp(20)));
            button.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        }
        measureBar.updateColors();
        center.invalidate();
    }

    private void openTools() {
        final boolean centerShown = center.getVisibility() == View.VISIBLE;
        ItemOptions.makeOptions(fragment, toolsButton)
                .add(R.drawable.msg_views, LocaleController.getString(centerShown ? R.string.PhogramMapsHideCenter : R.string.PhogramMapsShowCenter), () -> {
                    preferences().edit().putBoolean(KEY_CENTER, !centerShown).apply();
                    center.setVisibility(centerShown ? View.GONE : View.VISIBLE);
                })
                .add(R.drawable.phogram_measure_distance, LocaleController.getString(R.string.PhogramMeasureDistance), () -> start(false))
                .add(R.drawable.phogram_measure_area, LocaleController.getString(R.string.PhogramMeasureArea), () -> start(true))
                .show();
    }

    private void openLayers() {
        final boolean placesShown = preferences().getBoolean(KEY_PLACES, true);
        ItemOptions.makeOptions(fragment, layersButton)
                .add(R.drawable.msg_location, LocaleController.getString(placesShown ? R.string.PhogramMapsHidePlaces : R.string.PhogramMapsShowPlaces), () -> {
                    preferences().edit().putBoolean(KEY_PLACES, !placesShown).apply();
                    if (engine != null) engine.setPlacesShown(!placesShown);
                })
                .show();
    }

    private void start(boolean area) {
        measurement = new Measurement(area);
        center.setVisibility(View.VISIBLE);
        measureBar.setVisibility(View.VISIBLE);
        setRowShown(rowShown);
        onCameraMoved();
        host.onToolsChanged();
    }

    /** The map's point under the center point's middle. */
    private double[] centerPoint() {
        if (engine == null) return null;
        return engine.pointAt(center.getX() + center.getWidth() / 2f, center.getY() + center.getHeight() / 2f);
    }

    private static ImageView button(Context context, int icon, int description) {
        final ImageView button = new ImageView(context);
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(LocaleController.getString(description));
        ScaleStateListAnimator.apply(button);
        return button;
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("cogram_maps_tools", Context.MODE_PRIVATE);
    }

    /** A cross in the accent with a light halo, readable on the map in both themes, a dot at its middle. */
    private static final class CenterPoint extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CenterPoint(Context context) {
            super(context);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float arm = Math.min(cx, cy) - dp(2);
            final int[] colors = { 0xccffffff, Theme.getColor(Theme.key_chats_actionBackground) };
            final float[] widths = { dp(4.5f), dp(2) };
            for (int i = 0; i < 2; i++) {
                paint.setColor(colors[i]);
                paint.setStrokeWidth(widths[i]);
                canvas.drawLine(cx - arm, cy, cx - dp(4), cy, paint);
                canvas.drawLine(cx + dp(4), cy, cx + arm, cy, paint);
                canvas.drawLine(cx, cy - arm, cx, cy - dp(4), paint);
                canvas.drawLine(cx, cy + dp(4), cx, cy + arm, paint);
                canvas.drawCircle(cx, cy, widths[i] / 2 + dp(0.5f), paint);
            }
        }
    }
}
