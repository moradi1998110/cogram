package org.telegram.cogram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * A place in the map's search, in the shape of Telegram's search rows: the kind's emoji on its colored circle, the
 * name with the typed words in Telegram's blue, "type · address" under it, and the distance from the user.
 */
final class PlaceCell extends FrameLayout {

    private final MapKinds.IconView icon;
    private final TextView name;
    private final TextView details;
    private final TextView distance;
    private boolean divider;

    PlaceCell(Context context) {
        super(context);
        final boolean rtl = LocaleController.isRTL;
        icon = new MapKinds.IconView(context);
        addView(icon, LayoutHelper.createFrame(42, 42, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, rtl ? 0 : 14, 0, rtl ? 14 : 0, 0));

        distance = new TextView(context);
        distance.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        distance.setSingleLine(true);
        addView(distance, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, rtl ? 16 : 0, 0, rtl ? 0 : 16, 0));

        name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTypeface(AndroidUtilities.bold());
        name.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP, rtl ? 70 : 70, 11, rtl ? 70 : 70, 0));

        details = new TextView(context);
        details.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        details.setSingleLine(true);
        details.setEllipsize(TextUtils.TruncateAt.END);
        details.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        addView(details, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP, 70, 35, 70, 0));
        setWillNotDraw(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
    }

    /**
     * Shows a place; the query's words are colored in the name, and the distance is from the given position (none
     * without one).
     */
    void setPlace(MapEngine.Place place, String query, double[] from, boolean divider) {
        this.divider = divider;
        icon.setKind(place.kind);
        final int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        final SpannableStringBuilder title = new SpannableStringBuilder(place.name);
        if (query != null && !query.isEmpty()) {
            final String lower = place.name.toLowerCase();
            for (String word : query.toLowerCase().split("\\s+")) {
                if (word.isEmpty()) continue;
                final int at = lower.indexOf(word);
                if (at >= 0) {
                    title.setSpan(new ForegroundColorSpan(accent), at, at + word.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        name.setText(title);
        final StringBuilder line = new StringBuilder();
        if (!place.type.isEmpty()) line.append(place.type);
        if (place.address != null && !place.address.isEmpty()) {
            if (line.length() > 0) line.append(" · ");
            line.append(place.address);
        }
        details.setText(line);
        details.setVisibility(line.length() > 0 ? VISIBLE : GONE);
        if (from != null) {
            distance.setText(MapsFormat.distance((float) MapEngine.distance(from[0], from[1], place.latitude, place.longitude)));
            distance.setVisibility(VISIBLE);
        } else {
            distance.setVisibility(GONE);
        }
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        details.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        distance.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (divider) {
            final boolean rtl = LocaleController.isRTL;
            canvas.drawLine(rtl ? 0 : dp(70), getHeight() - 1, getWidth() - (rtl ? dp(70) : 0), getHeight() - 1, Theme.dividerPaint);
        }
    }
}
