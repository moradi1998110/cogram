package org.telegram.cogram.drawings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * A layer or a drawing in My drawings: its kind's icon in its color, its name and what it measures, then an eye
 * (shown in the accent color, hidden grey and struck through, as maps mark a layer) and ⋮ for its actions. While
 * drawings are being picked, a round check takes the icon's place and the eye and ⋮ step aside.
 */
public class DrawingCell extends FrameLayout {

    /** What a tap at some x falls on. */
    public static final int ROW = 0, EYE = 1, MORE = 2;

    private final ImageView icon;
    private final CheckBox2 check;
    private final TextView title;
    private final TextView subtitle;
    private final ImageView eye;
    private final ImageView more;
    private final Paint slash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean shown;
    private boolean divider;

    public DrawingCell(Context context) {
        super(context);
        setWillNotDraw(false);
        final boolean rtl = LocaleController.isRTL;
        final int start = (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL;
        final int end = (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL;

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        addView(icon, LayoutHelper.createFrame(24, 24, start, 21, 0, 21, 0));
        check = new CheckBox2(context, 21);
        check.setColor(-1, Theme.key_windowBackgroundWhite, Theme.key_checkboxCheck);
        check.setDrawUnchecked(true);
        check.setDrawBackgroundAsArc(10);
        check.setVisibility(GONE);
        addView(check, LayoutHelper.createFrame(24, 24, start, 21, 0, 21, 0));

        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        for (TextView text : new TextView[] { title, subtitle }) {
            text.setSingleLine();
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        }
        addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, rtl ? 96 : 70, 9, rtl ? 70 : 96, 0));
        addView(subtitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, rtl ? 96 : 70, 32, rtl ? 70 : 96, 0));

        more = button(context, R.drawable.ic_ab_other, R.string.AccDescrMoreOptions);
        addView(more, LayoutHelper.createFrame(44, LayoutHelper.MATCH_PARENT, end, 4, 0, 4, 0));
        eye = button(context, R.drawable.msg_views, R.string.PhogramDrawingsShowHide);
        addView(eye, LayoutHelper.createFrame(44, LayoutHelper.MATCH_PARENT, end, 48, 0, 48, 0));

        slash.setStyle(Paint.Style.STROKE);
        slash.setStrokeCap(Paint.Cap.ROUND);
        slash.setStrokeWidth(dp(1.66f));
        cut.setStyle(Paint.Style.STROKE);
        cut.setStrokeCap(Paint.Cap.ROUND);
        cut.setStrokeWidth(dp(4.66f));
    }

    private static ImageView button(Context context, int image, int description) {
        final ImageView button = new ImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(image);
        button.setContentDescription(LocaleController.getString(description));
        // Taps reach the list, which asks partAt() what they fell on.
        button.setClickable(false);
        button.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return button;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(58) + (divider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    public void set(int iconRes, int iconColor, CharSequence name, CharSequence about, boolean shown, boolean selecting, boolean selected, boolean animated, boolean divider) {
        icon.setImageResource(iconRes);
        icon.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        icon.setAlpha(shown ? 1f : 0.5f);
        title.setText(name);
        subtitle.setText(about);
        this.shown = shown;
        eye.setColorFilter(new PorterDuffColorFilter(Theme.getColor(shown ? Theme.key_windowBackgroundWhiteBlueIcon : Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
        more.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
        icon.setVisibility(selecting ? INVISIBLE : VISIBLE);
        check.setVisibility(selecting ? VISIBLE : GONE);
        check.setChecked(selected, animated);
        eye.setVisibility(selecting ? GONE : VISIBLE);
        more.setVisibility(selecting ? GONE : VISIBLE);
        if (this.divider != divider) {
            this.divider = divider;
            requestLayout();
        }
        invalidate();
    }

    /** What a tap at {@code x} falls on: the eye, ⋮ or the row. */
    public int partAt(float x) {
        if (eye.getVisibility() != VISIBLE) return ROW;
        if (x >= more.getLeft() && x < more.getRight()) return MORE;
        if (x >= eye.getLeft() && x < eye.getRight()) return EYE;
        return ROW;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!shown && eye.getVisibility() == VISIBLE) {
            // The eye struck through, the line cut out of it as Telegram's crossed icons are.
            final float cx = (eye.getLeft() + eye.getRight()) / 2f, cy = (eye.getTop() + eye.getBottom()) / 2f, d = dp(8);
            cut.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            slash.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, cut);
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, slash);
        }
        if (divider) {
            canvas.drawLine(LocaleController.isRTL ? 0 : dp(70), getHeight() - 1, getWidth() - (LocaleController.isRTL ? dp(70) : 0), getHeight() - 1, Theme.dividerPaint);
        }
    }

    /** The list's rows: {@code item.object} a layer or a drawing; {@code item.locked} picking, {@code item.checked} picked. */
    public static final class Factory extends UItem.UItemFactory<DrawingCell> {
        static {
            setup(new Factory());
        }

        public static UItem of(int id, Object object, boolean selecting, boolean selected) {
            final UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = object;
            item.locked = selecting;
            item.checked = selected;
            return item;
        }

        @Override
        public DrawingCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new DrawingCell(context);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            final DrawingCell cell = (DrawingCell) view;
            final boolean animated = cell.check.getVisibility() == VISIBLE && item.locked;
            if (item.object instanceof Drawing) {
                final Drawing drawing = (Drawing) item.object;
                cell.set(kindIcon(drawing.kind), drawing.color, DrawingSheet.title(drawing), DrawingSheet.describe(drawing),
                        !drawing.hidden, item.locked, item.checked, animated, divider);
            } else {
                final DrawingsStore.Layer layer = (DrawingsStore.Layer) item.object;
                cell.title.setTypeface(AndroidUtilities.bold());
                cell.set(R.drawable.msg_folders, Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), layer.name,
                        LocaleController.formatString(R.string.PhogramDrawingsCount, layer.count), layer.visible, item.locked, item.checked, animated, divider);
                return;
            }
            cell.title.setTypeface(null);
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.locked == b.locked && a.checked == b.checked && same(a.object, b.object);
        }

        private static boolean same(Object a, Object b) {
            if (a instanceof Drawing && b instanceof Drawing) {
                final Drawing x = (Drawing) a, y = (Drawing) b;
                return x.id.equals(y.id) && x.updated == y.updated && x.hidden == y.hidden;
            }
            if (a instanceof DrawingsStore.Layer && b instanceof DrawingsStore.Layer) {
                final DrawingsStore.Layer x = (DrawingsStore.Layer) a, y = (DrawingsStore.Layer) b;
                return x.id == y.id && x.visible == y.visible && x.count == y.count && x.name.equals(y.name);
            }
            return false;
        }
    }

    /** A landmark's pin, a line's or an area's measuring icon. */
    public static int kindIcon(int kind) {
        return kind == Drawing.AREA ? R.drawable.phogram_measure_area : kind == Drawing.LINE ? R.drawable.phogram_measure_distance : R.drawable.msg_location;
    }
}
