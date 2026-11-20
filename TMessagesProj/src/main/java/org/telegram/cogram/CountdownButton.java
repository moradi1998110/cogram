package org.telegram.cogram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;

/**
 * The way back to driving: a round button that, while counting down, shows the seconds left inside a ring that
 * shrinks with them, as Telegram's countdown for undoing; when the count ends it does what a tap does. Starting it
 * again counts from the top, so it waits while the driver keeps moving the map.
 */
final class CountdownButton extends ImageView {

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint number = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private long endsAt;
    private long length;
    private Runnable done;
    private final Runnable finish = () -> {
        endsAt = 0;
        setImageAlpha(255);
        invalidate();
        if (done != null) done.run();
    };

    CountdownButton(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2.5f));
        ring.setStrokeCap(Paint.Cap.ROUND);
        number.setTypeface(AndroidUtilities.bold());
        number.setTextSize(dp(16));
        number.setTextAlign(Paint.Align.CENTER);
    }

    void setColors(int ringColor, int numberColor) {
        ring.setColor(ringColor);
        number.setColor(numberColor);
        invalidate();
    }

    /** Counts down from the given seconds, then runs the action; again from the top if already counting. */
    void start(int seconds, Runnable action) {
        done = action;
        length = seconds * 1000L;
        endsAt = SystemClock.elapsedRealtime() + length;
        AndroidUtilities.cancelRunOnUIThread(finish);
        AndroidUtilities.runOnUIThread(finish, length);
        setImageAlpha(0);
        invalidate();
    }

    void stop() {
        AndroidUtilities.cancelRunOnUIThread(finish);
        endsAt = 0;
        setImageAlpha(255);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (endsAt == 0) return;
        final long left = Math.max(0, endsAt - SystemClock.elapsedRealtime());
        final float inset = dp(4);
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(rect, -90, 360f * left / Math.max(1, length), false, ring);
        final String seconds = String.valueOf((int) Math.ceil(left / 1000f));
        final Paint.FontMetrics metrics = number.getFontMetrics();
        canvas.drawText(seconds, getWidth() / 2f, getHeight() / 2f - (metrics.ascent + metrics.descent) / 2, number);
        if (left > 0) invalidate();
    }
}
