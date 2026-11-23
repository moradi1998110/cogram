package org.telegram.cogram;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * Phogram's mark assembling on the intro's first page, in place of Telegram's paper plane: it is drawn in white
 * over the intro's own Telegram-blue circle. The centre cell grows first, then the six cells around it one after
 * another, then the bridges join five of them into the "M", and the two free cells float once and settle.
 */
public class PhogramIntroMark extends View {

    // The mark's geometry, in its own units: cells of radius R, centres S apart, as everywhere in Phogram's mark.
    private static final float R = 36, S = 80, C30 = (float) Math.cos(Math.PI / 6);
    private static final float MARK_HEIGHT = 2 * S + 2 * R;
    /** The mark's height as a share of the circle's diameter. */
    private static final float FILL = 0.62f;
    // Centre, then the ring clockwise from the top: T, TR, BR, B, BL, TL.
    private static final float[][] CELLS = {
            {0, 0}, {0, -S}, {S * C30, -S / 2}, {S * C30, S / 2}, {0, S}, {-S * C30, S / 2}, {-S * C30, -S / 2}};
    private static final int CENTRE = 0, TOP = 1, BOTTOM = 4;
    // TL–BL, TR–BR, TL–C, C–TR.
    private static final int[][] BRIDGES = {{6, 5}, {2, 3}, {6, 0}, {0, 2}};

    private static final long CELL_DURATION = 520, RING_START = 260, RING_STEP = 90;
    private static final long BRIDGE_DURATION = 420, BRIDGE_START = 1050, BRIDGE_STEP = 110;
    private static final long FLOAT_DURATION = 900, FLOAT_START = 1700;
    private static final long TOTAL = FLOAT_START + FLOAT_DURATION;

    private static final CubicBezierInterpolator EASE = new CubicBezierInterpolator(0.2, 0.8, 0.2, 1);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] cells = new Path[CELLS.length];
    private final Path[] bridges = new Path[BRIDGES.length];
    private final float[][] bridgeCentres = new float[BRIDGES.length][];
    private ValueAnimator animator;
    /** Milliseconds into the assembly: nothing until it first plays; the finished mark once reattached. */
    private float time = 0;

    public PhogramIntroMark(Context context) {
        super(context);
        paint.setColor(0xFFFFFFFF);
        for (int i = 0; i < CELLS.length; i++) {
            cells[i] = MapKinds.hexagon(CELLS[i][0], CELLS[i][1], R, 5);
        }
        for (int i = 0; i < BRIDGES.length; i++) {
            final float[] a = CELLS[BRIDGES[i][0]], b = CELLS[BRIDGES[i][1]];
            bridges[i] = bridge(a[0], a[1], b[0], b[1]);
            bridgeCentres[i] = new float[] {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2};
        }
    }

    /** A pinched neck between two cell centres, as the mark's bridges: full at the cells, narrow at the waist. */
    private static Path bridge(float x1, float y1, float x2, float y2) {
        final float hE = 0.75f * R, hW = 0.36f * R, hc = 2 * hW - hE;
        final float length = (float) Math.hypot(x2 - x1, y2 - y1);
        final float nx = -(y2 - y1) / length, ny = (x2 - x1) / length;
        final float mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
        final Path path = new Path();
        path.moveTo(x1 + nx * hE, y1 + ny * hE);
        path.quadTo(mx + nx * hc, my + ny * hc, x2 + nx * hE, y2 + ny * hE);
        path.lineTo(x2 - nx * hE, y2 - ny * hE);
        path.quadTo(mx - nx * hc, my - ny * hc, x1 - nx * hE, y1 - ny * hE);
        path.close();
        return path;
    }

    /** Assembles the mark from nothing; called when the intro's first page comes into view. */
    public void play() {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(0, TOTAL);
        animator.setDuration(TOTAL);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            time = (float) a.getAnimatedValue();
            invalidate();
        });
        time = 0;
        invalidate();
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        time = TOTAL;
    }

    /** 0 before start, 1 after start + duration, eased in between. */
    private float progress(long start, long duration) {
        final float t = (time - start) / duration;
        return t <= 0 ? 0 : t >= 1 ? 1 : t;
    }

    /** A cell's growth: from nothing to a touch past its size, then back to it. */
    private static float grow(float t) {
        if (t < 0.7f) {
            return EASE.getInterpolation(t / 0.7f) * 1.08f;
        }
        return 1.08f - 0.08f * EASE.getInterpolation((t - 0.7f) / 0.3f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float diameter = Math.min(getWidth(), getHeight());
        if (diameter <= 0) {
            return;
        }
        final float scale = diameter * FILL / MARK_HEIGHT;
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(scale, scale);

        for (int i = 0; i < bridges.length; i++) {
            final float t = EASE.getInterpolation(progress(BRIDGE_START + i * BRIDGE_STEP, BRIDGE_DURATION));
            if (t > 0) {
                draw(canvas, bridges[i], bridgeCentres[i][0], bridgeCentres[i][1], 0.4f + 0.6f * t, 0, t);
            }
        }
        for (int i = 0; i < cells.length; i++) {
            final long start = i == CENTRE ? 0 : RING_START + (i - 1) * RING_STEP;
            final float t = progress(start, CELL_DURATION);
            if (t <= 0) {
                continue;
            }
            float dy = 0;
            if (i == TOP || i == BOTTOM) {
                // The two free cells float once, apart from the M, and settle back.
                final float f = progress(FLOAT_START, FLOAT_DURATION);
                dy = (float) Math.sin(Math.PI * f) * 6 * (i == TOP ? -1 : 1);
            }
            draw(canvas, cells[i], CELLS[i][0], CELLS[i][1], grow(t), dy, Math.min(1, t / 0.7f));
        }
        canvas.restore();
    }

    private void draw(Canvas canvas, Path path, float cx, float cy, float scale, float dy, float alpha) {
        canvas.save();
        canvas.translate(cx, cy + dy);
        canvas.scale(scale, scale);
        canvas.translate(-cx, -cy);
        paint.setAlpha((int) (255 * alpha));
        canvas.drawPath(path, paint);
        canvas.restore();
    }
}
