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
import org.telegram.cogram.drawings.Drawing;
import org.telegram.cogram.drawings.DrawingFormats;
import org.telegram.cogram.drawings.DrawingSheet;
import org.telegram.cogram.drawings.DrawingsActivity;
import org.telegram.cogram.drawings.DrawingsStore;
import org.telegram.cogram.drawings.SaveDrawingSheet;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

/**
 * The Maps tab's tools, laid out as Telegram's chat header: the search bar in the middle, the page's round "my location"
 * button at the line's start and, at its end, a capsule of two buttons as the header's call and ⋮ are: the layers, and
 * the tools (⋮). The tools draw at the center point: a landmark, a line or an area, measured as it goes; ✓ keeps it
 * with a name, a layer and a color. The kept drawings show on the map, open with a tap, and list in My drawings.
 */
public final class MapTools {

    public interface Host {
        /** The row or the drawing started or ended: the page lays its bars out again. */
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
    /** What is being drawn: a landmark, a line, an area; null when nothing is. */
    private Measurement measurement;
    private int kind;
    /** The kept drawing whose shape is being changed, or null for a new one. */
    private Drawing editing;
    private boolean rowShown;

    private final DrawingSheet.Actions actions = new DrawingSheet.Actions() {
        @Override
        public void editShape(Drawing drawing) {
            start(drawing.kind, drawing);
        }

        @Override
        public void showOnMap(Drawing drawing) {
            if (engine != null) engine.moveToBounds(drawing.bounds());
        }
    };

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

            @Override
            public void onSave() {
                save();
            }
        });
        measureBar.setVisibility(View.GONE);
        container.addView(measureBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 12, 0, 12, 0));
        DrawingsStore.get().addListener(this::showDrawings);
        updateColors();
    }

    public void setEngine(MapEngine engine) {
        this.engine = engine;
        if (engine == null) return;
        engine.setPlacesShown(preferences().getBoolean(KEY_PLACES, true));
        showDrawings();
    }

    /** The capsule shows with the search bar beside it. */
    public void setRowShown(boolean shown) {
        rowShown = shown;
        capsule.setVisibility(shown && !isMeasuring() ? View.VISIBLE : View.GONE);
    }

    public boolean isMeasuring() {
        return measurement != null;
    }

    /** Ends the drawing; false when there was none. */
    public boolean close() {
        if (measurement == null) return false;
        measurement = null;
        editing = null;
        if (engine != null) engine.showMeasure(null, false, null);
        measureBar.setVisibility(View.GONE);
        center.setVisibility(preferences().getBoolean(KEY_CENTER, false) ? View.VISIBLE : View.GONE);
        host.onToolsChanged();
        return true;
    }

    /** A kept drawing was tapped on the map. */
    public void openDrawing(String id) {
        final Drawing drawing = DrawingsStore.get().find(id);
        if (drawing != null && fragment.getParentActivity() != null) DrawingSheet.show(fragment.getParentActivity(), drawing, actions);
    }

    /** The row's distance from the page's bottom, and where the middle of the map's free part is. */
    public void layout(int bottomMargin, float centerOffsetY) {
        ((FrameLayout.LayoutParams) capsule.getLayoutParams()).bottomMargin = bottomMargin;
        ((FrameLayout.LayoutParams) measureBar.getLayoutParams()).bottomMargin = bottomMargin;
        center.setTranslationY(centerOffsetY);
    }

    /** The map moved: the line follows the center point to where the next point would go. */
    public void onCameraMoved() {
        if (measurement == null || engine == null || kind == Drawing.POINT) return;
        final double[] cursor = centerPoint();
        final double[] sides = measurement.sides(cursor);
        final String[] labels = new String[sides.length];
        for (int i = 0; i < sides.length; i++) {
            labels[i] = MeasureBar.length(sides[i]);
        }
        engine.showMeasure(measurement.coordinates(cursor), measurement.area, labels);
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

    private void showDrawings() {
        if (engine != null) engine.showDrawings(DrawingFormats.toGeoJson(DrawingsStore.get().visible()));
    }

    private void openTools() {
        final boolean centerShown = center.getVisibility() == View.VISIBLE;
        ItemOptions.makeOptions(fragment, toolsButton)
                .add(R.drawable.msg_views, LocaleController.getString(centerShown ? R.string.PhogramMapsHideCenter : R.string.PhogramMapsShowCenter), () -> {
                    preferences().edit().putBoolean(KEY_CENTER, !centerShown).apply();
                    center.setVisibility(centerShown ? View.GONE : View.VISIBLE);
                })
                .add(R.drawable.msg_location, LocaleController.getString(R.string.PhogramDrawingAddPoint), () -> start(Drawing.POINT, null))
                .add(R.drawable.phogram_measure_distance, LocaleController.getString(R.string.PhogramMeasureDistance), () -> start(Drawing.LINE, null))
                .add(R.drawable.phogram_measure_area, LocaleController.getString(R.string.PhogramMeasureArea), () -> start(Drawing.AREA, null))
                .add(R.drawable.msg_folders, LocaleController.getString(R.string.PhogramDrawingsTitle), () -> fragment.presentFragment(new DrawingsActivity(actions)))
                .show();
    }

    /** The landmarks, then each layer of drawings, shown or hidden with a tap. */
    private void openLayers() {
        final boolean placesShown = preferences().getBoolean(KEY_PLACES, true);
        final ItemOptions options = ItemOptions.makeOptions(fragment, layersButton)
                .add(R.drawable.msg_location, LocaleController.getString(placesShown ? R.string.PhogramMapsHidePlaces : R.string.PhogramMapsShowPlaces), () -> {
                    preferences().edit().putBoolean(KEY_PLACES, !placesShown).apply();
                    if (engine != null) engine.setPlacesShown(!placesShown);
                });
        for (DrawingsStore.Layer layer : DrawingsStore.get().layers()) {
            options.add(layer.visible ? R.drawable.msg_views : R.drawable.msg_archive_hide, layer.name,
                    () -> DrawingsStore.get().setLayerVisible(layer.id, !layer.visible));
        }
        options.show();
    }

    /** Starts drawing {@code kind}, a new one or {@code drawing}'s shape again. */
    private void start(int kind, Drawing drawing) {
        this.kind = kind;
        editing = drawing;
        measurement = drawing != null && kind != Drawing.POINT
                ? Measurement.of(kind == Drawing.AREA, drawing.coordinates) : new Measurement(kind == Drawing.AREA);
        center.setVisibility(View.VISIBLE);
        measureBar.setVisibility(View.VISIBLE);
        setRowShown(rowShown);
        if (drawing != null && engine != null) engine.moveToBounds(drawing.bounds());
        if (kind == Drawing.POINT) {
            measureBar.setPoint();
        } else {
            onCameraMoved();
        }
        host.onToolsChanged();
    }

    /** ✓: a changed shape is kept at once; a new drawing asks for its name, layer and color first. */
    private void save() {
        final double[] coordinates = kind == Drawing.POINT ? centerPoint() : measurement.coordinates(null);
        if (coordinates == null || fragment.getParentActivity() == null) return;
        final Drawing drawing = editing != null ? editing : Drawing.create(kind, coordinates, Theme.getColor(Theme.key_color_blue));
        close();
        if (drawing.id != null && DrawingsStore.get().find(drawing.id) != null) {
            drawing.coordinates = coordinates;
            drawing.updated = System.currentTimeMillis();
            DrawingsStore.get().save(drawing);
            saved();
            return;
        }
        SaveDrawingSheet.show(fragment.getParentActivity(), drawing, this::saved);
    }

    private void saved() {
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.PhogramDrawingSaved)).show();
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
