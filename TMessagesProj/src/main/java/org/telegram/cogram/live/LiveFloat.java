package org.telegram.cogram.live;

import android.app.Activity;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.telegram.cogram.FloatingWindow;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

/**
 * The broadcast going on in the floating window after its screen is left, while the rest of Phogram is used. A tap
 * opens its screen again with the same player, × ends it. A call turns its sound off, and back on after.
 */
final class LiveFloat implements FloatingWindow.Listener, NotificationCenter.NotificationCenterDelegate {

    private static LiveFloat current;

    private final LiveChannel channel;
    private final WebView player;
    private final FloatingWindow window;
    private boolean mutedByCall;

    private LiveFloat(Activity activity, LiveChannel channel, WebView player) {
        this.channel = channel;
        this.player = player;
        // Out of sight, as when the app is left without drawing over other apps allowed, it is never heard.
        final FrameLayout content = new FrameLayout(activity) {
            @Override
            protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                if (visibility == VISIBLE) {
                    player.onResume();
                } else {
                    player.onPause();
                }
            }
        };
        content.addView(player);
        // Its screen's clients go with the screen; the player crashing ends the window.
        player.setWebChromeClient(null);
        player.setWebViewClient(new LiveTvActivity.PlayerClient(() -> {
            if (current == this) end();
        }));
        window = new FloatingWindow(activity, 9 / 16f, this);
        window.setContent(content);
        window.show();
        // Its screen paused it on the way out.
        player.onResume();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didStartedCall);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didEndCall);
    }

    /** The screen of {@code channel} is left while it plays: its player floats on. */
    static void show(Activity activity, LiveChannel channel, WebView player) {
        end();
        current = new LiveFloat(activity, channel, player);
    }

    /** The floating player, when it plays {@code channel}: the opening screen takes it, and the window goes. */
    static WebView take(LiveChannel channel) {
        if (current == null) return null;
        if (!current.channel.id.equals(channel.id)) {
            end();
            return null;
        }
        final WebView player = current.player;
        current.release();
        current = null;
        player.onResume();
        return player;
    }

    private static void end() {
        if (current == null) return;
        final LiveFloat ending = current;
        current = null;
        ending.release();
        LiveTvActivity.destroyPlayer(ending.player);
    }

    private void release() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didStartedCall);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didEndCall);
        window.hide();
        AndroidUtilities.removeFromParent(player);
    }

    @Override
    public void onOpen() {
        final BaseFragment last = LaunchActivity.getLastFragment();
        if (last != null) last.presentFragment(new LiveTvActivity(channel));
    }

    @Override
    public void onClose() {
        if (current == this) end();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didStartedCall) {
            player.evaluateJavascript("muted", value -> {
                if (!"false".equals(value) || current != this) return;
                mutedByCall = true;
                player.evaluateJavascript("cmd('mute')", null);
            });
        } else if (id == NotificationCenter.didEndCall && mutedByCall) {
            mutedByCall = false;
            player.evaluateJavascript("cmd('unMute')", null);
        }
    }
}
