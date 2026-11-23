package org.telegram.cogram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.pip.PipSource;
import org.telegram.messenger.pip.source.IPipSourceDelegate;
import org.telegram.messenger.pip.utils.PipActions;
import org.telegram.messenger.pip.utils.PipUtils;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * The trip in a small window while Phogram is out of sight, the map with the next turn and the speed. As Telegram does
 * for its videos: in its own floating window over other apps when drawing over them is allowed, else in the system's
 * picture-in-picture through Telegram's PipSource. The map itself moves into the window and back, so it goes on
 * following the car.
 */
final class TripMini implements IPipSourceDelegate {

    interface Host extends TripService.Actions {
        /** The map is back on its page: the page lays its padding out again. */
        void onMapBack();
    }

    private static final int ACTION_VOICE = 1;
    private static final int ACTION_END = 2;

    private final Activity activity;
    private final MapEngine engine;
    private final Host host;
    private final FrameLayout view;
    private final FrameLayout mapSlot;
    private final MapKinds.TurnView turn;
    private final MapsRouteUi.Figure distance;
    private final MapKinds.SpeedView speed;
    private final View anchor;
    private final PipSource source;

    private ViewGroup mapHome;
    private int mapIndex;
    private boolean floating;

    TripMini(Activity activity, MapEngine engine, ViewGroup page, Host host) {
        this.activity = activity;
        this.engine = engine;
        this.host = host;
        final Context context = activity;

        view = new FrameLayout(context);
        view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        mapSlot = new FrameLayout(context) {
            @Override
            protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                // The system's window was closed while the trip goes on: the map stops drawing until it is seen again.
                if (mapHome == null) return;
                if (visibility == VISIBLE) {
                    engine.onResume();
                } else {
                    engine.onPause();
                }
            }

            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                // The car sits low in the small window too, the road ahead above it.
                if (mapHome != null) engine.setDriverAnchor(h / 3);
            }
        };
        view.addView(mapSlot, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // The next turn in a small card of the trip's green strip.
        final FrameLayout card = new FrameLayout(context);
        final GradientDrawable green = new GradientDrawable();
        green.setCornerRadius(dp(12));
        green.setColor(Theme.getColor(Theme.key_returnToCallBackground));
        card.setBackground(green);
        final int onGreen = Theme.getColor(Theme.key_returnToCallText);
        final boolean rtl = LocaleController.isRTL;
        turn = new MapKinds.TurnView(context);
        turn.setColor(onGreen);
        card.addView(turn, LayoutHelper.createFrame(30, 30, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 6, 0, 6, 0));
        distance = new MapsRouteUi.Figure(context, 20, rtl ? Gravity.RIGHT : Gravity.LEFT);
        distance.setTextColor(onGreen);
        card.addView(distance, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 28, Gravity.CENTER_VERTICAL, rtl ? 8 : 40, 0, rtl ? 40 : 8, 0));
        view.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 42, Gravity.TOP, 6, 6, 6, 0));

        speed = new MapKinds.SpeedView(context);
        view.addView(speed, LayoutHelper.createFrame(48, 48, Gravity.BOTTOM | (rtl ? Gravity.RIGHT : Gravity.LEFT), 6, 0, 6, 6));

        // Where the system's window grows from, and its shape: a 3:4 frame in the middle of the map's page.
        anchor = new View(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int width = MeasureSpec.getSize(widthMeasureSpec);
                setMeasuredDimension(width, Math.min(MeasureSpec.getSize(heightMeasureSpec), width * 4 / 3));
            }
        };
        page.addView(anchor, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        // With drawing over other apps allowed, Telegram's floating window is used instead, as for its videos.
        source = AndroidUtilities.checkInlinePermissions(activity) ? null : new PipSource.Builder(activity, this)
                .setTagPrefix("phogram-trip")
                .setPriority(-1)
                .setCornerRadius(dp(12))
                .setContentView(anchor)
                .setActionListener(this::onAction)
                .build();
    }

    void update(MapEngine.NavigationState state) {
        if (state.rerouting || state.next == null) {
            turn.setTurn(MapEngine.Maneuver.STRAIGHT, 0);
            distance.clear();
        } else {
            turn.setTurn(state.next);
            distance.set(MapsFormat.distanceParts(state.toNext), true);
        }
        speed.set(state.speed, state.speedLimit);
    }

    /** The voice's button in the system's window follows the trip's own. */
    void onVoiceChanged() {
        if (source != null) source.invalidateActions();
    }

    /** The map's page shows or leaves: with the app gone to the background, the floating window takes the trip. */
    void onPageShown(boolean shown) {
        if (shown) {
            hideFloating();
        } else if (source == null && ApplicationLoader.mainInterfacePaused && AndroidUtilities.checkInlinePermissions(activity)) {
            showFloating();
        }
    }

    void destroy() {
        hideFloating();
        if (source != null) source.destroy();
        returnMap();
        AndroidUtilities.removeFromParent(anchor);
    }

    // The map moves between its page and the small window.

    private void takeMap() {
        final View map = engine.getView();
        if (mapHome != null || map == null || !(map.getParent() instanceof ViewGroup)) return;
        mapHome = (ViewGroup) map.getParent();
        mapIndex = mapHome.indexOfChild(map);
        mapHome.removeView(map);
        mapSlot.addView(map, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        engine.onResume();
    }

    private void returnMap() {
        if (mapHome == null) return;
        final View map = engine.getView();
        final ViewGroup home = mapHome;
        mapHome = null;
        AndroidUtilities.removeFromParent(map);
        home.addView(map, Math.min(mapIndex, home.getChildCount()), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        host.onMapBack();
        // Back on a page that is not shown, as when the trip ends from the notification, the map rests; else it draws
        // again at once on its page (leaving the small window paused it).
        if (ApplicationLoader.mainInterfacePaused) {
            engine.onPause();
        } else {
            engine.onResume();
        }
    }

    private Bitmap mapFrame() {
        final View texture = findTexture(engine.getView());
        return texture instanceof android.view.TextureView ? ((android.view.TextureView) texture).getBitmap() : null;
    }

    private static View findTexture(View view) {
        if (view instanceof android.view.TextureView) return view;
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View found = findTexture(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // Telegram's floating window, over other apps.

    private WindowManager.LayoutParams floatingParams;

    @SuppressLint("ClickableViewAccessibility")
    private void showFloating() {
        if (floating) return;
        final WindowManager wm = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
        if (floatingParams == null) {
            floatingParams = PipUtils.createWindowLayoutParams(activity, false);
            floatingParams.width = Math.max(dp(150), AndroidUtilities.displaySize.x * 2 / 5);
            floatingParams.height = floatingParams.width * 4 / 3;
            floatingParams.x = AndroidUtilities.displaySize.x - floatingParams.width - dp(12);
            floatingParams.y = AndroidUtilities.statusBarHeight + dp(56);
            view.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
                }
            });
            view.setClipToOutline(true);
            view.setElevation(dp(6));
            // Telegram's small close button, as on its floating videos: the window leaves until the app is left again.
            final ImageView close = new ImageView(activity);
            close.setImageResource(R.drawable.miniplayer_close);
            close.setScaleType(ImageView.ScaleType.CENTER);
            close.setBackground(Theme.createCircleDrawable(dp(28), 0x66000000));
            close.setContentDescription(LocaleController.getString(R.string.Close));
            close.setOnClickListener(v -> hideFloating());
            view.addView(close, LayoutHelper.createFrame(36, 36, Gravity.BOTTOM | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));
            view.setOnTouchListener(new FloatingDrag());
        }
        try {
            AndroidUtilities.removeFromParent(view);
            takeMap();
            wm.addView(view, floatingParams);
            floating = true;
        } catch (Throwable e) {
            FileLog.e(e);
            returnMap();
        }
    }

    private void hideFloating() {
        if (!floating) return;
        floating = false;
        try {
            final WindowManager wm = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(view);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        returnMap();
    }

    /** The floating window is dragged anywhere on the screen; a tap opens Phogram on the trip. */
    private final class FloatingDrag implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = floatingParams.x;
                    startY = floatingParams.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    final float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > ViewConfiguration.get(v.getContext()).getScaledTouchSlop()) {
                        dragging = true;
                    }
                    if (dragging && floating) {
                        floatingParams.x = Math.max(0, Math.min(AndroidUtilities.displaySize.x - floatingParams.width, startX + (int) dx));
                        floatingParams.y = Math.max(0, Math.min(AndroidUtilities.displaySize.y - floatingParams.height, startY + (int) dy));
                        try {
                            ((WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(view, floatingParams);
                        } catch (Throwable ignore) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) openApp();
                    return true;
            }
            return false;
        }
    }

    private void openApp() {
        final Intent intent = new Intent(activity, LaunchActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // The system's picture-in-picture, through Telegram's PipSource.

    private void onAction(int action) {
        if (action == ACTION_END) {
            host.endTrip();
        } else if (action == ACTION_VOICE) {
            host.toggleVoice();
        }
    }

    @Override
    public void pipCreateActionsList(ArrayList<RemoteAction> output, String sourceId, int maxActions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || maxActions < 2) return;
        final boolean muted = VoiceGuide.isMuted();
        final String voice = LocaleController.getString(muted ? R.string.PhogramNavVoice : R.string.PhogramNavVoiceOff);
        output.add(PipActions.create(activity, sourceId, ACTION_VOICE, voice, voice,
                Icon.createWithResource(activity, muted ? R.drawable.msg_unmute : R.drawable.msg_mute)));
        final String end = LocaleController.getString(R.string.PhogramNavEnd);
        output.add(PipActions.create(activity, sourceId, ACTION_END, end, end, Icon.createWithResource(activity, R.drawable.msg_cancel)));
    }

    @Override
    public Bitmap pipCreatePrimaryWindowViewBitmap() {
        return mapFrame();
    }

    @Override
    public View pipCreatePictureInPictureView() {
        AndroidUtilities.removeFromParent(view);
        return view;
    }

    @Override
    public void pipHidePrimaryWindowView(Runnable firstFrameCallback) {
        takeMap();
        AndroidUtilities.doOnPreDraw(view, firstFrameCallback);
    }

    @Override
    public Bitmap pipCreatePictureInPictureViewBitmap() {
        return mapFrame();
    }

    @Override
    public void pipShowPrimaryWindowView(Runnable firstFrameCallback) {
        returnMap();
        final View map = engine.getView();
        if (map != null) {
            AndroidUtilities.doOnPreDraw(map, firstFrameCallback);
        } else {
            firstFrameCallback.run();
        }
    }
}
