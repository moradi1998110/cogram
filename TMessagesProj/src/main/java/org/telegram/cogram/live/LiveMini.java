package org.telegram.cogram.live;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.app.RemoteAction;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.pip.PipSource;
import org.telegram.messenger.pip.source.IPipSourceDelegate;
import org.telegram.messenger.pip.utils.PipActions;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * The live broadcast in the system's picture-in-picture window while Phogram is out of sight, through Telegram's
 * PipSource as for its own videos. The player itself moves into the window and back, so the broadcast goes on without
 * loading again. A trip's window comes first: its priority is the higher.
 */
final class LiveMini implements IPipSourceDelegate {

    private static final int ACTION_MUTE = 1;

    private final WebView player;
    private final ViewGroup home;
    private final FrameLayout window;
    private final PipSource source;

    private boolean playing;
    private boolean shown;
    private boolean muted = true;

    LiveMini(Activity activity, ViewGroup home, WebView player) {
        this.home = home;
        this.player = player;
        window = new FrameLayout(activity);
        window.setBackgroundColor(Color.BLACK);
        source = new PipSource.Builder(activity, this)
                .setTagPrefix("phogram-live")
                .setPriority(-2)
                .setCornerRadius(dp(12))
                .setContentView(home)
                .setActionListener(this::onAction)
                .build();
    }

    /** The broadcast plays: the window may open. */
    void setPlaying(boolean playing) {
        this.playing = playing;
        source.invalidateAvailability();
    }

    /** The live screen is on top: only then does leaving the app open the window. */
    void setShown(boolean shown) {
        this.shown = shown;
        source.invalidateAvailability();
    }

    boolean isOpen() {
        return source.state2.isAttachedToPip();
    }

    void destroy() {
        source.destroy();
        moveTo(home);
    }

    private void onAction(int action) {
        if (action != ACTION_MUTE) return;
        muted = !muted;
        player.evaluateJavascript(muted ? "cmd('mute')" : "cmd('unMute')", null);
        source.invalidateActions();
    }

    private void moveTo(ViewGroup parent) {
        if (player.getParent() == parent) return;
        AndroidUtilities.removeFromParent(player);
        parent.addView(player, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    /** A black frame where the window grows from: the video itself cannot be drawn into a picture. */
    private Bitmap frame() {
        final Bitmap bitmap = Bitmap.createBitmap(Math.max(1, home.getWidth()), Math.max(1, home.getHeight()), Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLACK);
        return bitmap;
    }

    @Override
    public boolean pipIsAvailable() {
        return playing && shown;
    }

    @Override
    public void pipCreateActionsList(ArrayList<RemoteAction> output, String sourceId, int maxActions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || maxActions < 1) return;
        final String title = LocaleController.getString(muted ? R.string.Unmute : R.string.Mute);
        output.add(PipActions.create(player.getContext(), sourceId, ACTION_MUTE, title, title,
                Icon.createWithResource(player.getContext(), muted ? R.drawable.msg_unmute : R.drawable.msg_mute)));
    }

    @Override
    public Bitmap pipCreatePrimaryWindowViewBitmap() {
        return frame();
    }

    @Override
    public View pipCreatePictureInPictureView() {
        AndroidUtilities.removeFromParent(window);
        return window;
    }

    @Override
    public void pipHidePrimaryWindowView(Runnable firstFrameCallback) {
        moveTo(window);
        // Leaving the app may have paused it before the window took it.
        player.onResume();
        // The sound may have been turned on in the player itself: its button shows what is so now.
        player.evaluateJavascript("muted", value -> {
            muted = !"false".equals(value);
            source.invalidateActions();
        });
        AndroidUtilities.doOnPreDraw(window, firstFrameCallback);
    }

    @Override
    public Bitmap pipCreatePictureInPictureViewBitmap() {
        return frame();
    }

    @Override
    public void pipShowPrimaryWindowView(Runnable firstFrameCallback) {
        moveTo(home);
        // The window closed without the app coming back: the broadcast stops, never sound without its picture.
        if (ApplicationLoader.mainInterfacePaused) player.onPause();
        AndroidUtilities.doOnPreDraw(home, firstFrameCallback);
    }
}
