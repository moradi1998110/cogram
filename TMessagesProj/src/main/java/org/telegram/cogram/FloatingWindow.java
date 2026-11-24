package org.telegram.cogram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.pip.utils.PipUtils;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

/**
 * A small window at the screen's top right, the way Telegram floats a call: over all of Phogram's pages without any
 * permission, and over other apps too once drawing over them is allowed. It is dragged anywhere and settles at the
 * nearer side; a tap opens what it shows, × closes it. One shows at a time: a new one closes the one before.
 */
public final class FloatingWindow {

    public interface Listener {
        /** Tapped: the app is brought to the front first when it was left. */
        void onOpen();

        /** Closed by ×, by another window taking its place, or by the app's window going away. */
        void onClose();
    }

    private static FloatingWindow front;

    private final Activity activity;
    private final Listener listener;
    private final float ratio;
    private final FrameLayout view;
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private Application.ActivityLifecycleCallbacks lifecycle;

    /** {@code ratio}: the window's height to its width. */
    @SuppressLint("ClickableViewAccessibility")
    public FloatingWindow(Activity activity, float ratio, Listener listener) {
        this.activity = activity;
        this.ratio = ratio;
        this.listener = listener;
        view = new FrameLayout(activity);
        view.setBackgroundColor(Color.BLACK);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
            }
        });
        view.setClipToOutline(true);
        view.setElevation(dp(6));

        // Over the content, so a drag or a tap is the window's and never the player's or the map's.
        final View cover = new View(activity);
        cover.setOnTouchListener(new Drag());
        view.addView(cover, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Telegram's small close button, as on its floating videos.
        final ImageView close = new ImageView(activity);
        close.setImageResource(R.drawable.miniplayer_close);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setBackground(Theme.createCircleDrawable(dp(28), 0x66000000));
        close.setContentDescription(LocaleController.getString(R.string.Close));
        close.setOnClickListener(v -> close());
        view.addView(close, LayoutHelper.createFrame(36, 36, Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));
    }

    /** What the window shows, under its cover and its close button. */
    public void setContent(View content) {
        AndroidUtilities.removeFromParent(content);
        view.addView(content, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    public boolean isShown() {
        return windowManager != null;
    }

    public void show() {
        if (isShown()) return;
        if (front != null) front.close();
        // Over other apps when allowed, as Telegram's calls; else in the app's own window, needing no permission.
        final boolean overApps = AndroidUtilities.checkInlinePermissions(activity);
        final WindowManager wm = (WindowManager) (overApps ? ApplicationLoader.applicationContext : activity).getSystemService(Context.WINDOW_SERVICE);
        if (params == null) {
            params = PipUtils.createWindowLayoutParams(activity, !overApps);
            params.width = Math.max(dp(150), AndroidUtilities.displaySize.x * 2 / 5);
            params.height = (int) (params.width * ratio);
            params.x = AndroidUtilities.displaySize.x - params.width - dp(8);
            params.y = AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() + dp(8);
        } else {
            params.type = PipUtils.getWindowLayoutParamsType(activity, !overApps);
        }
        try {
            wm.addView(view, params);
            windowManager = wm;
            front = this;
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        if (!overApps) {
            // A window in the app's own window goes with it.
            lifecycle = new Lifecycle() {
                @Override
                public void onActivityDestroyed(Activity destroyed) {
                    if (destroyed == activity) close();
                }
            };
            activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        }
    }

    /** Leaves the screen, its content kept: the caller takes it back. */
    public void hide() {
        if (!isShown()) return;
        if (lifecycle != null) activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        lifecycle = null;
        try {
            windowManager.removeView(view);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        windowManager = null;
        if (front == this) front = null;
    }

    private void close() {
        hide();
        listener.onClose();
    }

    private void open() {
        if (ApplicationLoader.mainInterfacePaused) {
            final Intent intent = new Intent(activity, LaunchActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(intent);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        listener.onOpen();
    }

    private void moveTo(int x, int y) {
        if (!isShown()) return;
        params.x = Math.max(0, Math.min(AndroidUtilities.displaySize.x - params.width, x));
        params.y = Math.max(0, Math.min(AndroidUtilities.displaySize.y - params.height, y));
        try {
            windowManager.updateViewLayout(view, params);
        } catch (Throwable ignore) {
        }
    }

    private final class Drag implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    final float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > ViewConfiguration.get(v.getContext()).getScaledTouchSlop()) {
                        dragging = true;
                    }
                    if (dragging) moveTo(startX + (int) dx, startY + (int) dy);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        open();
                    } else {
                        settle();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) settle();
                    return true;
            }
            return false;
        }

        /** To the nearer side, as Telegram's call window goes. */
        private void settle() {
            final int left = dp(8), right = AndroidUtilities.displaySize.x - params.width - dp(8);
            final int to = params.x + params.width / 2 < AndroidUtilities.displaySize.x / 2 ? left : right;
            final ValueAnimator animator = ValueAnimator.ofInt(params.x, to);
            animator.addUpdateListener(a -> moveTo((int) a.getAnimatedValue(), params.y));
            animator.setDuration(200);
            animator.start();
        }
    }

    private abstract static class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityResumed(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    }
}
