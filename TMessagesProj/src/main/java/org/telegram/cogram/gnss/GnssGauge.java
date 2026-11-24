package org.telegram.cogram.gnss;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import org.telegram.ui.ActionBar.Theme;

/**
 * The position's quality around the "my location" button, as a speedometer: an arc whose length is the accuracy and
 * whose color is its level, in Telegram's green, yellow and red; a faint track shows the whole dial.
 */
public final class GnssGauge extends Drawable {

    private static final float START = 135;
    private static final float SWEEP = 270;

    private final GnssMonitor monitor;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public GnssGauge(GnssMonitor monitor) {
        this.monitor = monitor;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(2.5f));
    }

    @Override
    public void draw(Canvas canvas) {
        final float inset = dp(4);
        rect.set(getBounds());
        rect.inset(inset, inset);
        paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
        paint.setAlpha(50);
        canvas.drawArc(rect, START, SWEEP, false, paint);
        final float gauge = monitor.getGauge();
        if (gauge <= 0) return;
        paint.setColor(Theme.getColor(color(monitor.getQuality())));
        canvas.drawArc(rect, START, SWEEP * gauge, false, paint);
    }

    static int color(int quality) {
        switch (quality) {
            case GnssMonitor.GOOD: return Theme.key_color_green;
            case GnssMonitor.FAIR: return Theme.key_color_yellow;
            case GnssMonitor.POOR: return Theme.key_color_red;
            default: return Theme.key_windowBackgroundWhiteGrayIcon;
        }
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
