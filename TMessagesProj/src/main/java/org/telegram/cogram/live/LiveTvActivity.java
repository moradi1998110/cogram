package org.telegram.cogram.live;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * A channel's current live broadcast in YouTube's own embedded player, the way YouTube's terms allow:
 * its ads and logo stay, and the sound stops when the app leaves the screen.
 */
public class LiveTvActivity extends BaseFragment {

    private static final String PAGE = "<!DOCTYPE html><html><head>"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<style>html,body{margin:0;height:100%%;background:#000}iframe{width:100%%;height:100%%;border:0}</style>"
            + "</head><body><iframe id=\"p\" src=\"%s\" referrerpolicy=\"strict-origin-when-cross-origin\""
            + " allow=\"autoplay; encrypted-media; picture-in-picture; fullscreen\" allowfullscreen></iframe>"
            // The player's own messages keep "muted" true to what it plays; cmd() sends it a command (mute, unMute).
            + "<script>var p=document.getElementById('p'),muted=true;"
            + "function send(m){p.contentWindow.postMessage(JSON.stringify(m),'*')}"
            + "function cmd(f){send({event:'command',func:f,args:[]})}"
            + "p.onload=function(){send({event:'listening',id:'p'})};"
            + "addEventListener('message',function(e){try{var i=JSON.parse(e.data).info;"
            + "if(i&&typeof i.muted=='boolean')muted=i.muted}catch(x){}});</script></body></html>";

    private final LiveChannel channel;

    private WebView webView;
    private LiveMini mini;
    private TextView errorView;
    private FrameLayout fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    public LiveTvActivity(LiveChannel channel) {
        this.channel = channel;
    }

    /** YouTube wants an embedding app to name itself: an https address made of the app's package. */
    private static String origin() {
        return "https://" + ApplicationLoader.applicationContext.getPackageName();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(channel.name);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        // The player keeps YouTube's 16:9 at any width.
        final FrameLayout player = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int width = MeasureSpec.getSize(widthMeasureSpec);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
            }
        };
        player.setBackgroundColor(Color.BLACK);
        layout.addView(player, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        webView = new WebView(context);
        webView.setBackgroundColor(Color.BLACK);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new FullscreenClient());
        webView.setWebViewClient(new PlayerClient());
        player.addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        mini = new LiveMini(getParentActivity(), player, webView);

        errorView = new TextView(context);
        errorView.setTextColor(Color.WHITE);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.GONE);
        player.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final TextView source = new TextView(context);
        source.setText(LocaleController.formatString(R.string.PhogramLiveTvSource, channel.name));
        source.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        source.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        source.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        layout.addView(source, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 14));

        // Always there: the way out when a channel blocks embedding or is not live right now.
        final ButtonWithCounterView openButton = new ButtonWithCounterView(context, getResourceProvider());
        openButton.setText(LocaleController.getString(R.string.PhogramLiveTvOpenYoutube), false);
        openButton.setOnClickListener(v -> Browser.openUrl(context, channel.youtubeUrl()));
        layout.addView(openButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 0));

        LiveStreamResolver.resolve(channel, this::play);
        return fragmentView = layout;
    }

    private void play(String videoId) {
        if (webView == null) return;
        if (videoId == null) {
            showError(R.string.PhogramLiveTvNotLive);
            return;
        }
        webView.loadDataWithBaseURL(origin() + "/", String.format(PAGE, LiveChannel.embedUrl(videoId, origin())), "text/html", "UTF-8", null);
        mini.setPlaying(true);
    }

    private void showError(int text) {
        errorView.setText(LocaleController.getString(text));
        errorView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Leaving the app for the small window, the broadcast goes on in it.
        if (webView != null && (mini == null || !mini.isOpen())) webView.onPause();
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        if (mini != null) mini.setShown(true);
    }

    @Override
    public void onBecomeFullyHidden() {
        super.onBecomeFullyHidden();
        if (mini != null) mini.setShown(false);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (fullscreenView != null) {
            if (invoked) exitFullscreen(true);
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        exitFullscreen(false);
        if (mini != null) mini.destroy();
        if (webView == null) return;
        try {
            final ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
        } catch (Exception e) {
            FileLog.e(e);
        }
        webView = null;
    }

    /** The player's fullscreen button: its video over the whole window, turned sideways. */
    private void enterFullscreen(View video, WebChromeClient.CustomViewCallback callback) {
        if (fullscreenView != null || getParentActivity() == null) {
            callback.onCustomViewHidden();
            return;
        }
        fullscreenView = new FrameLayout(getParentActivity());
        fullscreenView.setBackgroundColor(Color.BLACK);
        fullscreenView.addView(video, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fullscreenCallback = callback;
        decorView().addView(fullscreenView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getParentActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /** {@code tellPlayer}: when the app, not the player, leaves fullscreen, the player must hear of it. */
    private void exitFullscreen(boolean tellPlayer) {
        if (fullscreenView == null) return;
        decorView().removeView(fullscreenView);
        fullscreenView = null;
        if (getParentActivity() != null) {
            getParentActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        if (tellPlayer && fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
        }
        fullscreenCallback = null;
    }

    private ViewGroup decorView() {
        return (ViewGroup) getParentActivity().getWindow().getDecorView();
    }

    private class FullscreenClient extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            enterFullscreen(view, callback);
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen(false);
        }
    }

    private class PlayerClient extends WebViewClient {
        /** Taps that leave the player, such as YouTube's logo, open outside the app. */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            Browser.openUrl(view.getContext(), request.getUrl().toString());
            return true;
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view == webView) {
                mini.destroy();
                mini = null;
                ((ViewGroup) view.getParent()).removeView(view);
                view.destroy();
                webView = null;
                showError(R.string.PhogramLiveTvError);
            }
            return true;
        }
    }
}
