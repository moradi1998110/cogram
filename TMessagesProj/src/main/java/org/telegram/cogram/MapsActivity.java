package org.telegram.cogram;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.utils.ViewOutlineProviderImpl;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LineProgressView;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

/**
 * Phogram's Maps tab, in the slot where Telegram has Settings (Settings opens from the chats page's options
 * menu, as Telegram does when Calls holds this slot).
 *
 * In builds with a map engine the page shows Telegram's progress circle until the tab is first opened, then
 * makes the map, so the engine costs nothing to users who never open the tab. The map fills the page under
 * the header and the tab bar; the header gets the page's color so its title stays readable over the map, and
 * the map's buttons are Telegram's round location-page buttons. Builds without a map engine show an empty
 * state in Telegram's style.
 *
 * While a map is shown, swipes on it move the map instead of switching tabs.
 */
public class MapsActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private static final int LOCATION_PERMISSION_REQUEST = 7301;
    private static final String LOCATION_ASKED_KEY = "phogramMapsLocationAsked";
    private static final long COMPASS_CHECK_MS = 250;

    private FrameLayout contentView;
    private FrameLayout mapContainer;
    private View headerBackground;
    private View headerDivider;
    private LinearLayout emptyView;
    private ImageView emptyIcon;
    private TextView emptyTitle;
    private TextView emptyText;
    private RadialProgressView progressView;
    private ImageView locationButton;
    private CompassButton compassButton;
    private FrameLayout downloadCard;
    private TextView downloadTitle;
    private TextView downloadSubtitle;
    private TextView downloadButton;
    private LineProgressView downloadProgress;
    private View mapView;

    private MapEngine mapEngine;
    private boolean mapEngineRequested;
    private boolean pageResumed;

    private boolean hasMainTabs;
    private int statusBarHeight;
    private int navigationBarHeight;

    private final Runnable compassCheck = new Runnable() {
        @Override
        public void run() {
            updateCompass();
            if (pageResumed && mapEngine != null) {
                AndroidUtilities.runOnUIThread(this, COMPASS_CHECK_MS);
            }
        }
    };

    private final Runnable createMapEngine = this::createMapEngine;

    public MapsActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        statusBarHeight = AndroidUtilities.statusBarHeight;
        navigationBarHeight = AndroidUtilities.navigationBarHeight;

        contentView = new FrameLayout(context);

        if (!hasMainTabs) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        }
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PhogramMapsTab));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        actionBar.setAddToContainer(false);
        actionBar.setOccupyStatusBar(true);
        actionBar.setBackgroundColor(Color.TRANSPARENT);
        actionBar.setBackground(null);

        // The map fills the whole page; the header and the tab bar float above it.
        mapContainer = new FrameLayout(context);
        contentView.addView(mapContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(32), 0, dp(32), 0);

        emptyIcon = new ImageView(context);
        emptyIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        emptyIcon.setImageResource(R.drawable.msg_location);
        emptyIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        emptyIcon.setPadding(dp(22), dp(22), dp(22), dp(22));
        // The blue gradient of Telegram's own settings icons.
        final GradientDrawable iconBackground = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0xFF1BA4ED, 0xFF1488E1 });
        iconBackground.setShape(GradientDrawable.OVAL);
        emptyIcon.setBackground(iconBackground);
        emptyView.addView(emptyIcon, LayoutHelper.createLinear(88, 88, Gravity.CENTER_HORIZONTAL));

        emptyTitle = new TextView(context);
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        emptyTitle.setTypeface(AndroidUtilities.bold());
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setText(getString(R.string.PhogramMapsTab));
        emptyView.addView(emptyTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 18, 0, 0));

        emptyText = new TextView(context);
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setText(getString(R.string.PhogramMapsEmpty));
        emptyView.addView(emptyText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        progressView = new RadialProgressView(context, getResourceProvider());
        contentView.addView(progressView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        // Behind the title over the map. The map is drawn on its own surface, which the glass of Telegram's
        // headers cannot blur, so the header takes the page's color with Telegram's divider.
        headerBackground = new View(context);
        headerBackground.setVisibility(View.GONE);
        contentView.addView(headerBackground, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        headerDivider = new View(context);
        headerDivider.setVisibility(View.GONE);
        contentView.addView(headerDivider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));

        // The location page's round buttons: the device's position at the bottom, north up at the top when the
        // map is turned.
        locationButton = new ImageView(context);
        ScaleStateListAnimator.apply(locationButton);
        locationButton.setTranslationZ(dp(2));
        locationButton.setOutlineProvider(ViewOutlineProviderImpl.BOUNDS_OVAL);
        locationButton.setImageResource(R.drawable.msg_current_location);
        locationButton.setScaleType(ImageView.ScaleType.CENTER);
        locationButton.setContentDescription(getString(R.string.AccDescrMyLocation));
        locationButton.setVisibility(View.GONE);
        locationButton.setOnClickListener(v -> onLocationButtonClick());
        contentView.addView(locationButton, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 12, 12));

        compassButton = new CompassButton(context);
        ScaleStateListAnimator.apply(compassButton);
        compassButton.setTranslationZ(dp(2));
        compassButton.setOutlineProvider(ViewOutlineProviderImpl.BOUNDS_OVAL);
        compassButton.setContentDescription(getString(R.string.PhogramMapsNorthUp));
        compassButton.setVisibility(View.GONE);
        compassButton.setOnClickListener(v -> {
            if (mapEngine != null) {
                mapEngine.resetRotation();
                updateCompass();
            }
        });
        contentView.addView(compassButton, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.TOP, 0, 12, 12, 0));

        // The offer of a map file to download, left of the location button: Telegram's white rounded panel with
        // its blue button and line progress.
        downloadCard = new FrameLayout(context);
        downloadCard.setTranslationZ(dp(2));
        downloadCard.setVisibility(View.GONE);
        final LinearLayout downloadTexts = new LinearLayout(context);
        downloadTexts.setOrientation(LinearLayout.VERTICAL);
        downloadTitle = new TextView(context);
        downloadTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        downloadTitle.setTypeface(AndroidUtilities.bold());
        downloadTitle.setSingleLine(true);
        downloadTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        downloadTexts.addView(downloadTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        downloadSubtitle = new TextView(context);
        downloadSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        downloadSubtitle.setSingleLine(true);
        downloadSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        downloadTexts.addView(downloadSubtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        // The texts take what the button leaves, so a long name or size ends with an ellipsis before the button.
        final LinearLayout downloadRow = new LinearLayout(context);
        downloadRow.setOrientation(LinearLayout.HORIZONTAL);
        downloadRow.setGravity(Gravity.CENTER_VERTICAL);
        downloadRow.addView(downloadTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        downloadCard.addView(downloadRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 14, 9, 12, 11));
        downloadButton = new TextView(context);
        downloadButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        downloadButton.setTypeface(AndroidUtilities.bold());
        downloadButton.setGravity(Gravity.CENTER);
        downloadButton.setPadding(dp(14), 0, dp(14), 0);
        ScaleStateListAnimator.apply(downloadButton);
        downloadButton.setOnClickListener(v -> {
            if (mapEngine == null) return;
            final MapEngine.Download offer = mapEngine.getOfferedDownload();
            if (offer == null) return;
            if (offer.downloading) {
                mapEngine.cancelOfferedDownload();
            } else {
                mapEngine.startOfferedDownload();
            }
        });
        downloadRow.addView(downloadButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        downloadProgress = new LineProgressView(context);
        downloadCard.addView(downloadProgress, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.BOTTOM, 12, 0, 12, 4));
        contentView.addView(downloadCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 12, 0, 64, 12));

        final boolean engineAvailable = MapEngines.isAvailable();
        emptyView.setVisibility(engineAvailable ? View.GONE : View.VISIBLE);
        progressView.setVisibility(engineAvailable ? View.VISIBLE : View.GONE);

        updateColors();
        updateLayout();
        ViewCompat.setOnApplyWindowInsetsListener(contentView, this::onApplyWindowInsets);
        return fragmentView = contentView;
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        if (MapEngines.isAvailable() && !mapEngineRequested) {
            mapEngineRequested = true;
            // One frame for the progress circle first: the engine's first start holds the main thread for a moment.
            AndroidUtilities.runOnUIThread(createMapEngine, 32);
        }
    }

    private void createMapEngine() {
        if (contentView == null || getParentActivity() == null) {
            mapEngineRequested = false;
            return;
        }
        try {
            mapEngine = MapEngines.create(getParentActivity());
        } catch (Throwable e) {
            FileLog.e(e);
            mapEngine = null;
        }
        if (mapEngine == null) {
            progressView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        // The map stays hidden behind the progress circle until it can draw; its surface is black before that.
        mapContainer.setVisibility(View.INVISIBLE);
        setMapView(mapEngine.getView());
        applyMapPadding();
        if (pageResumed) {
            mapEngine.onResume();
            AndroidUtilities.runOnUIThread(compassCheck, COMPASS_CHECK_MS);
        }
        mapEngine.setDownloadListener(this::updateDownloadCard);
        mapEngine.setOnReadyListener(this::onMapReady);
    }

    private void updateDownloadCard() {
        if (downloadCard == null) return;
        final MapEngine.Download offer = mapEngine != null && mapContainer.getVisibility() == View.VISIBLE ? mapEngine.getOfferedDownload() : null;
        if (offer == null) {
            downloadCard.setVisibility(View.GONE);
            return;
        }
        downloadTitle.setText(offer.name);
        if (offer.downloading) {
            downloadSubtitle.setText(LocaleController.formatString(R.string.PhogramMapsDownloading, (int) offer.progress));
            downloadButton.setText(getString(R.string.Cancel));
            downloadProgress.setVisibility(View.VISIBLE);
            downloadProgress.setProgress(offer.progress / 100f, true);
        } else {
            downloadSubtitle.setText(LocaleController.formatString(R.string.PhogramMapsOffline, offer.size));
            downloadButton.setText(getString(R.string.PhogramMapsDownload));
            downloadProgress.setVisibility(View.GONE);
            downloadProgress.setProgress(0, false);
        }
        downloadCard.setVisibility(View.VISIBLE);
    }

    private void onMapReady() {
        if (contentView == null || mapEngine == null) return;
        progressView.setVisibility(View.GONE);
        mapContainer.setVisibility(View.VISIBLE);
        locationButton.setVisibility(View.VISIBLE);
        updateCompass();
        updateDownloadCard();
    }

    private void applyMapPadding() {
        if (mapEngine != null) {
            final int[] padding = getMapPadding();
            mapEngine.setPadding(padding[0], padding[1], padding[2], padding[3]);
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(compassCheck);
        AndroidUtilities.cancelRunOnUIThread(createMapEngine);
        if (mapEngine != null) {
            mapEngine.onDestroy();
            mapEngine = null;
        }
    }

    /** Shows a map on this page in place of the empty state, or the empty state again for null. */
    public void setMapView(View view) {
        if (mapContainer == null || view == mapView) return;
        if (mapView != null) {
            mapContainer.removeView(mapView);
        }
        mapView = view;
        if (view != null) {
            AndroidUtilities.removeFromParent(view);
            mapContainer.addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        final boolean hasMap = view != null;
        emptyView.setVisibility(hasMap ? View.GONE : View.VISIBLE);
        headerBackground.setVisibility(hasMap ? View.VISIBLE : View.GONE);
        headerDivider.setVisibility(hasMap ? View.VISIBLE : View.GONE);
        if (!hasMap) {
            locationButton.setVisibility(View.GONE);
        }
        updateCompass();
    }

    /** How much of the top and bottom edges the header and the tab bar cover, for the map's own padding. */
    public int[] getMapPadding() {
        final int top = statusBarHeight + ActionBar.getCurrentActionBarHeight();
        final int bottom = navigationBarHeight + (hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0);
        return new int[] { 0, top, 0, bottom };
    }

    private void updateLayout() {
        if (contentView == null) return;
        final int[] padding = getMapPadding();
        // The empty state and the progress circle center in the part of the page the bars leave free.
        emptyView.setPadding(dp(32), padding[1], dp(32), padding[3]);
        progressView.setTranslationY((padding[1] - padding[3]) / 2f);

        headerBackground.getLayoutParams().height = padding[1];
        final FrameLayout.LayoutParams divider = (FrameLayout.LayoutParams) headerDivider.getLayoutParams();
        divider.height = Math.max(1, AndroidUtilities.getShadowHeight());
        divider.topMargin = padding[1];

        ((FrameLayout.LayoutParams) locationButton.getLayoutParams()).bottomMargin = padding[3] + dp(12);
        ((FrameLayout.LayoutParams) downloadCard.getLayoutParams()).bottomMargin = padding[3] + dp(12);
        ((FrameLayout.LayoutParams) compassButton.getLayoutParams()).topMargin = padding[1] + dp(12);
        contentView.requestLayout();
    }

    private void updateColors() {
        if (contentView == null) return;
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        emptyTitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        emptyText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        progressView.setProgressColor(getThemedColor(Theme.key_progressCircle));
        headerBackground.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        headerDivider.setBackgroundColor(getThemedColor(Theme.key_divider));

        locationButton.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(40), getThemedColor(Theme.key_location_actionBackground), getThemedColor(Theme.key_location_actionPressedBackground)));
        locationButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_location_actionActiveIcon), PorterDuff.Mode.MULTIPLY));
        compassButton.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(40), getThemedColor(Theme.key_location_actionBackground), getThemedColor(Theme.key_location_actionPressedBackground)));
        final GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setCornerRadius(dp(12));
        cardBackground.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        downloadCard.setBackground(cardBackground);
        downloadTitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        downloadSubtitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        downloadButton.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        downloadButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(15), getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
        downloadProgress.setProgressColor(getThemedColor(Theme.key_featuredStickers_addButton));
        downloadProgress.setBackColor(getThemedColor(Theme.key_divider));
        compassButton.setColors(getThemedColor(Theme.key_text_RedRegular), getThemedColor(Theme.key_location_actionIcon));
    }

    private void updateCompass() {
        if (compassButton == null) return;
        final float rotation = mapEngine != null ? mapEngine.getRotation() : 0;
        final boolean turned = mapView != null && mapContainer.getVisibility() == View.VISIBLE && Math.abs(normalize(rotation)) > 0.5f;
        compassButton.setNeedleRotation(rotation);
        if (turned != (compassButton.getVisibility() == View.VISIBLE)) {
            compassButton.setVisibility(turned ? View.VISIBLE : View.GONE);
        }
    }

    private static float normalize(float degrees) {
        float d = degrees % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private void onLocationButtonClick() {
        if (mapEngine == null) return;
        final Activity activity = getParentActivity();
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 23 && !hasLocationPermission(activity)) {
            // The permission is asked for here, when the user wants the position, not when the tab opens.
            final boolean askedBefore = MessagesController.getGlobalMainSettings().getBoolean(LOCATION_ASKED_KEY, false);
            if (askedBefore && !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Android no longer shows its question once it was refused for good, so point to Settings as Telegram does.
                showPermissionAlert();
            } else {
                MessagesController.getGlobalMainSettings().edit().putBoolean(LOCATION_ASKED_KEY, true).apply();
                activity.requestPermissions(new String[] { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION }, LOCATION_PERMISSION_REQUEST);
            }
            return;
        }
        mapEngine.moveToMyLocation();
    }

    private static boolean hasLocationPermission(Activity activity) {
        return activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != LOCATION_PERMISSION_REQUEST) return;
        final Activity activity = getParentActivity();
        if (activity != null && mapEngine != null && hasLocationPermission(activity)) {
            mapEngine.onLocationPermissionGranted();
            mapEngine.moveToMyLocation();
        }
    }

    private void showPermissionAlert() {
        if (getParentActivity() == null) return;
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTopAnimation(R.raw.permission_request_location, AlertsCreator.PERMISSIONS_REQUEST_TOP_ICON_SIZE, false, getThemedColor(Theme.key_dialogTopBackground));
        builder.setMessage(AndroidUtilities.replaceTags(getString(R.string.PhogramMapsLocationPermission)));
        builder.setNegativeButton(getString(R.string.PermissionOpenSettings), (dialog, which) -> {
            if (getParentActivity() == null) return;
            try {
                final Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + ApplicationLoader.applicationContext.getPackageName()));
                getParentActivity().startActivity(intent);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        builder.setPositiveButton(getString(R.string.OK), null);
        showDialog(builder.create());
    }

    @Override
    public void onResume() {
        super.onResume();
        pageResumed = true;
        updateColors();
        if (mapEngine != null) {
            mapEngine.onResume();
            AndroidUtilities.cancelRunOnUIThread(compassCheck);
            AndroidUtilities.runOnUIThread(compassCheck, COMPASS_CHECK_MS);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        pageResumed = false;
        AndroidUtilities.cancelRunOnUIThread(compassCheck);
        // The map pauses so it does not use the battery while another tab is open.
        if (mapEngine != null) {
            mapEngine.onPause();
        }
    }

    @Override
    public boolean isLightStatusBar() {
        final int key = mapView != null ? Theme.key_windowBackgroundWhite : Theme.key_windowBackgroundGray;
        return AndroidUtilities.computePerceivedBrightness(getThemedColor(key)) > 0.721f;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @NonNull
    private WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);
        statusBarHeight = systemInsets.top;
        navigationBarHeight = systemInsets.bottom;
        updateLayout();
        applyMapPadding();
        return WindowInsetsCompat.CONSUMED;
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        // With a map shown, a swipe moves the map; without one the tabs slide as on any other page.
        return mapView == null;
    }

    /** North up button: a compass needle, red to the north, turned with the map. */
    private static final class CompassButton extends View {

        private final Paint northPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint southPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path north = new Path();
        private final Path south = new Path();
        private float needleRotation;

        CompassButton(Context context) {
            super(context);
        }

        void setColors(int northColor, int southColor) {
            northPaint.setColor(northColor);
            southPaint.setColor(southColor);
            invalidate();
        }

        void setNeedleRotation(float degrees) {
            if (needleRotation != degrees) {
                needleRotation = degrees;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float half = dp(5);
            final float length = dp(11);
            north.rewind();
            north.moveTo(cx, cy - length);
            north.lineTo(cx + half, cy);
            north.lineTo(cx - half, cy);
            north.close();
            south.rewind();
            south.moveTo(cx, cy + length);
            south.lineTo(cx - half, cy);
            south.lineTo(cx + half, cy);
            south.close();
            canvas.save();
            canvas.rotate(needleRotation, cx, cy);
            canvas.drawPath(north, northPaint);
            canvas.drawPath(south, southPaint);
            canvas.restore();
        }
    }
}
