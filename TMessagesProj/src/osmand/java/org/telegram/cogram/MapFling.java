package org.telegram.cogram;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Scroller;

import org.maplibre.android.maps.MapLibreMap;

/**
 * A one-finger drag let go while moving glides on and slows to a stop, as a zoomed photo does in Telegram's
 * PhotoViewer: Android's Scroller from the finger's own speed, with Android's own fling thresholds. MapLibre's fling
 * went on at about a quarter of that speed, so the map seemed to brake as the finger lifted.
 * A second finger, a zoom by one finger, a new touch, or the app moving the camera leave it to MapLibre.
 */
final class MapFling implements View.OnTouchListener, Runnable {

    private final View view;
    private final MapLibreMap map;
    private final Runnable onMove;
    private final Runnable onEnd;
    private final Scroller scroller;
    private final int minVelocity;
    private final int maxVelocity;

    private VelocityTracker tracker;
    private boolean twoFingers;
    private double zoomAtDown;
    private int lastX;
    private int lastY;
    private boolean touchStoppedGlide;

    /**
     * {@code onMove} runs at each step of a glide and {@code onEnd} at its stop, as the camera's move and idle do
     * during MapLibre's own gestures: its scrollBy tells neither.
     */
    @SuppressLint("ClickableViewAccessibility")
    MapFling(View view, MapLibreMap map, Runnable onMove, Runnable onEnd) {
        this.view = view;
        this.map = map;
        this.onMove = onMove;
        this.onEnd = onEnd;
        final ViewConfiguration configuration = ViewConfiguration.get(view.getContext());
        minVelocity = configuration.getScaledMinimumFlingVelocity();
        maxVelocity = configuration.getScaledMaximumFlingVelocity();
        scroller = new Scroller(view.getContext());
        map.getUiSettings().setFlingVelocityAnimationEnabled(false);
        // The touches only pass by: MapLibre still gets every one of them.
        view.setOnTouchListener(this);
        // The app moving the camera (a place, the position, the car followed) takes it from the glide.
        map.addOnCameraMoveStartedListener(reason -> {
            if (reason != MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) stop();
        });
    }

    /** Whether a glide was going on, now stopped. */
    boolean stop() {
        if (scroller.isFinished()) return false;
        scroller.abortAnimation();
        view.removeCallbacks(this);
        return true;
    }

    /** The touch that has just ended stopped a glide: its tap only stops the map, as in Google Maps. */
    boolean touchStoppedGlide() {
        return touchStoppedGlide;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStoppedGlide = stop();
                twoFingers = false;
                zoomAtDown = map.getCameraPosition().zoom;
                recycle();
                tracker = VelocityTracker.obtain();
                tracker.addMovement(event);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                twoFingers = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracker != null) tracker.addMovement(event);
                break;
            case MotionEvent.ACTION_UP:
                if (tracker != null) {
                    tracker.addMovement(event);
                    tracker.computeCurrentVelocity(1000, maxVelocity);
                    // Zooming by one finger (a double tap held and dragged) changes the zoom, not the place.
                    if (!twoFingers && map.getCameraPosition().zoom == zoomAtDown) {
                        start(tracker.getXVelocity(), tracker.getYVelocity());
                    }
                }
                recycle();
                break;
            case MotionEvent.ACTION_CANCEL:
                recycle();
                break;
        }
        return false;
    }

    private void start(float velocityX, float velocityY) {
        if (Math.hypot(velocityX, velocityY) < minVelocity) return;
        lastX = 0;
        lastY = 0;
        scroller.fling(0, 0, Math.round(velocityX), Math.round(velocityY),
                Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        view.postOnAnimation(this);
    }

    /** One frame of the glide: the map moves by what the Scroller went since the last frame. */
    @Override
    public void run() {
        if (!scroller.computeScrollOffset()) {
            onEnd.run();
            return;
        }
        final int x = scroller.getCurrX();
        final int y = scroller.getCurrY();
        map.scrollBy(x - lastX, y - lastY);
        onMove.run();
        lastX = x;
        lastY = y;
        view.postOnAnimation(this);
    }

    private void recycle() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }
}
