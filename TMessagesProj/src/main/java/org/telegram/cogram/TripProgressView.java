package org.telegram.cogram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * How far the trip has come: a rounded bar filled toward the destination (from the right in right-to-left languages)
 * with a soft gradient in the route's color, a car on the head of the fill that glides with each new position, the
 * stops on the way as dots (filled once passed) and a flag at the destination. Each trip starts it from zero; it is
 * redrawn only when the progress moves.
 */
final class TripProgressView extends View {

    private static final long GLIDE_MS = 900;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int color = 0xFF2EB85C;
    private int trackColor = 0x1F000000;
    private int dotColor = 0xFFFFFFFF;
    private float[] stops = new float[0];
    private float shown;
    private float target;
    private ValueAnimator glide;
    private LinearGradient gradient;
    private int gradientWidth;
    private int gradientColor;
    private boolean gradientRtl;

    TripProgressView(Context context) {
        super(context);
    }

    /** A new trip: the route's color, its stops as fractions of the way, and the bar back at the start. */
    void reset(int routeColor, float[] stopFractions) {
        color = routeColor;
        stops = stopFractions == null ? new float[0] : stopFractions;
        if (glide != null) glide.cancel();
        shown = 0;
        target = 0;
        gradient = null;
        invalidate();
    }

    /** The stops still on the way after a new route, as fractions of it. */
    void setStops(float[] stopFractions) {
        stops = stopFractions == null ? new float[0] : stopFractions;
        invalidate();
    }

    void setColors(int track, int dot) {
        trackColor = track;
        dotColor = dot;
        invalidate();
    }

    /** The progress from 0 to 1; the car glides there from where it is. */
    void setProgress(float progress) {
        progress = Math.max(0, Math.min(1, progress));
        if (Math.abs(progress - target) < 0.0005f) return;
        target = progress;
        if (glide != null) glide.cancel();
        final float from = shown;
        glide = ValueAnimator.ofFloat(0, 1);
        glide.setDuration(GLIDE_MS);
        glide.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        glide.addUpdateListener(a -> {
            shown = from + (target - from) * (float) a.getAnimatedValue();
            invalidate();
        });
        glide.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(26), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (glide != null) glide.cancel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final boolean rtl = LocaleController.isRTL;
        final float icon = dp(20);
        // Room at both ends for the car at the start and the flag at the end.
        final float left = icon / 2f + dp(2);
        final float right = getWidth() - icon / 2f - dp(2);
        final float width = right - left;
        final float cy = getHeight() / 2f + dp(3);
        final float bar = dp(6);
        final float radius = bar / 2f;
        rect.set(left, cy - radius, right, cy + radius);
        paint.setShader(null);
        paint.setColor(trackColor);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // The way done, its color growing toward the head.
        final float head = rtl ? right - width * shown : left + width * shown;
        if (shown > 0) {
            if (gradient == null || gradientWidth != getWidth() || gradientColor != color || gradientRtl != rtl) {
                final int light = ColorUtils.blendARGB(color, 0xFFFFFFFF, 0.55f);
                gradient = rtl
                        ? new LinearGradient(right, 0, left, 0, light, color, Shader.TileMode.CLAMP)
                        : new LinearGradient(left, 0, right, 0, light, color, Shader.TileMode.CLAMP);
                gradientWidth = getWidth();
                gradientColor = color;
                gradientRtl = rtl;
            }
            rect.set(rtl ? head : left, cy - radius, rtl ? right : head, cy + radius);
            paint.setShader(gradient);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);
        }

        // The stops: a ring ahead, a full dot once passed.
        for (float stop : stops) {
            final float x = rtl ? right - width * stop : left + width * stop;
            final boolean passed = stop <= shown;
            paint.setColor(passed ? color : dotColor);
            canvas.drawCircle(x, cy, dp(5), paint);
            paint.setColor(passed ? dotColor : color);
            canvas.drawCircle(x, cy, dp(passed ? 2 : 3), paint);
        }

        // The flag at the destination, then the car on the head, facing the destination.
        MapKinds.drawEmoji(canvas, "🏁", rtl ? left : right, cy - dp(6), dp(16));
        canvas.save();
        if (!rtl) {
            // The car of the emoji faces left; going right it is turned around.
            canvas.scale(-1, 1, head, cy);
        }
        MapKinds.drawEmoji(canvas, "🚗", head, cy - dp(7), icon);
        canvas.restore();
    }
}
