package org.telegram.cogram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import org.telegram.messenger.AndroidUtilities;

/**
 * The map's heading as a strip of the 360 degrees across the top, as in games' compasses: ticks every 5 degrees, the
 * directions' letters and every 30 degrees' number, fading out to both ends, with the heading at the center. Marks
 * on its edge show where the user is heading and where the route turns next. Dragging it turns the map, a fling
 * keeps it turning for a moment, and a tap asks the page to put north up or to follow the road again.
 */
final class CompassStrip extends View {

    interface Listener {
        /** The strip was dragged or flung: the map turns to this bearing. */
        void onRotate(float bearing);

        /** The strip was tapped. */
        void onTap();
    }

    private static final String[] LETTERS = { "N", "E", "S", "W" };

    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path triangle = new Path();
    private final float pxPerDegree = dp(2.4f);
    private final int touchSlop;
    private Listener listener;

    private int textColor;
    private int northColor;
    private int headingColor;
    private int targetColor;

    /** The map's bearing shown, and the marks: the user's heading and the next turn's direction (NaN hides one). */
    private float bearing;
    private float heading = Float.NaN;
    private float shownHeading = Float.NaN;
    private float target = Float.NaN;
    private float shownTarget = Float.NaN;

    private VelocityTracker velocity;
    private float downX;
    private float lastX;
    private boolean dragging;
    private boolean touching;
    /** Degrees per second while the strip slows down after a fling. */
    private float flingSpeed;
    private long lastFrame;

    CompassStrip(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        tick.setStrokeCap(Paint.Cap.ROUND);
        letter.setTextAlign(Paint.Align.CENTER);
        letter.setTypeface(AndroidUtilities.bold());
        letter.setTextSize(dp(13));
        number.setTextAlign(Paint.Align.CENTER);
        number.setTextSize(dp(11));
        setClickable(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * The strip's colors; over the bare map a soft shadow keeps it readable on light and dark tiles, on a card there
     * is none.
     */
    void setColors(int text, int north, int heading, int target, int shadow) {
        textColor = text;
        northColor = north;
        headingColor = heading;
        targetColor = target;
        final float radius = shadow == 0 ? 0 : dp(1.5f);
        tick.setShadowLayer(radius, 0, 0, shadow);
        letter.setShadowLayer(radius, 0, 0, shadow);
        number.setShadowLayer(radius, 0, 0, shadow);
        mark.setShadowLayer(radius, 0, 0, shadow);
        invalidate();
    }

    /** The map's bearing, as the camera reports it; while the user drags or flings, the strip keeps its own. */
    void setBearing(float degrees) {
        if (touching || flingSpeed != 0) return;
        degrees = normalize(degrees);
        if (Math.abs(delta(bearing, degrees)) < 0.05f) return;
        bearing = degrees;
        invalidate();
    }

    void setMarks(float heading, float target) {
        this.heading = heading;
        this.target = target;
        if (Float.isNaN(heading)) shownHeading = Float.NaN;
        if (Float.isNaN(target)) shownTarget = Float.NaN;
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // The map under the strip would take the drag as a pan, and the page's tabs as a swipe.
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                dragging = false;
                flingSpeed = 0;
                downX = lastX = event.getX();
                if (velocity == null) velocity = VelocityTracker.obtain();
                velocity.clear();
                velocity.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocity != null) velocity.addMovement(event);
                final float x = event.getX();
                if (!dragging && Math.abs(x - downX) > touchSlop) {
                    dragging = true;
                    lastX = x;
                }
                if (dragging) {
                    // The strip moves with the finger: pulled to the left, the degrees on the right come to the center.
                    turnBy(-(x - lastX) / pxPerDegree);
                    lastX = x;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                touching = false;
                if (!dragging) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    if (listener != null) listener.onTap();
                } else if (velocity != null) {
                    velocity.addMovement(event);
                    velocity.computeCurrentVelocity(1000);
                    final float speed = -velocity.getXVelocity() / pxPerDegree;
                    if (Math.abs(speed) > 30) {
                        flingSpeed = Math.max(-720, Math.min(720, speed));
                        lastFrame = 0;
                        postInvalidateOnAnimation();
                    }
                }
                recycleVelocity();
                return true;
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                recycleVelocity();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    /** Turns by some degrees, with a light tick as each of the four directions passes the center. */
    private void turnBy(float degrees) {
        if (degrees == 0) return;
        final float before = bearing;
        bearing = normalize(bearing + degrees);
        if ((int) Math.floor((before + 45) / 90) != (int) Math.floor((before + degrees + 45) / 90)) {
            try {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {
            }
        }
        if (listener != null) listener.onRotate(bearing);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = System.nanoTime();
        boolean moving = false;
        if (flingSpeed != 0) {
            final float seconds = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1e9f);
            turnBy(flingSpeed * seconds);
            // Slows down as a fling in a list does, and stops once barely moving.
            flingSpeed *= (float) Math.pow(0.04, seconds);
            if (Math.abs(flingSpeed) < 8) flingSpeed = 0;
            moving = flingSpeed != 0;
        }
        lastFrame = now;
        shownHeading = ease(shownHeading, heading);
        shownTarget = ease(shownTarget, target);
        moving |= !Float.isNaN(heading) && Math.abs(delta(shownHeading, heading)) > 0.1f;
        moving |= !Float.isNaN(target) && Math.abs(delta(shownTarget, target)) > 0.1f;

        final float width = getWidth();
        final float center = width / 2f;
        final float half = center - dp(4);
        final float tickTop = dp(2);
        final float baseline = getHeight() - dp(3);
        final float span = half / pxPerDegree;
        final int first = (int) Math.ceil((bearing - span) / 5) * 5;
        for (int d = first; d <= bearing + span; d += 5) {
            final float x = center + (d - bearing) * pxPerDegree;
            final int alpha = fade(Math.abs(x - center) / half);
            if (alpha <= 0) continue;
            final int angle = ((d % 360) + 360) % 360;
            final boolean major = angle % 15 == 0;
            tick.setColor(textColor);
            tick.setAlpha(alpha * (major ? 220 : 130) / 255);
            tick.setStrokeWidth(major ? dp(1.3f) : dp(1));
            canvas.drawLine(x, tickTop, x, tickTop + (major ? dp(7) : dp(4)), tick);
            if (angle % 90 == 0) {
                letter.setColor(angle == 0 ? northColor : textColor);
                letter.setAlpha(alpha);
                canvas.drawText(LETTERS[angle / 90], x, baseline, letter);
            } else if (angle % 30 == 0) {
                number.setColor(textColor);
                number.setAlpha(alpha * 180 / 255);
                canvas.drawText(String.valueOf(angle), x, baseline - dp(1), number);
            }
        }
        // The center: where the top of the map points.
        tick.setColor(textColor);
        tick.setAlpha(255);
        tick.setStrokeWidth(dp(2));
        canvas.drawLine(center, 0, center, dp(10), tick);
        drawMark(canvas, shownTarget, targetColor, center, half);
        drawMark(canvas, shownHeading, headingColor, center, half);
        if (moving) postInvalidateOnAnimation();
    }

    /** A small triangle on the strip's top edge, held at the end when its direction is out of view. */
    private void drawMark(Canvas canvas, float direction, int color, float center, float half) {
        if (Float.isNaN(direction)) return;
        final float x = center + Math.max(-half, Math.min(half, delta(bearing, direction) * pxPerDegree));
        final float size = dp(4);
        triangle.rewind();
        triangle.moveTo(x - size, 0);
        triangle.lineTo(x + size, 0);
        triangle.lineTo(x, size * 1.4f);
        triangle.close();
        mark.setColor(color);
        canvas.drawPath(triangle, mark);
    }

    /** Fully drawn in the middle, fading out over the outer third of each side. */
    private static int fade(float fromCenter) {
        if (fromCenter >= 1) return 0;
        if (fromCenter <= 0.6f) return 255;
        return (int) (255 * (1 - (fromCenter - 0.6f) / 0.4f));
    }

    /** One step of the marks toward their new direction, the short way round, so they glide rather than jump. */
    private static float ease(float shown, float wanted) {
        if (Float.isNaN(wanted)) return Float.NaN;
        if (Float.isNaN(shown)) return wanted;
        return normalize(shown + delta(shown, wanted) * 0.2f);
    }

    /** From one direction to another the short way round, -180 to 180. */
    private static float delta(float from, float to) {
        float d = (to - from) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private static float normalize(float degrees) {
        final float d = degrees % 360;
        return d < 0 ? d + 360 : d;
    }
}
