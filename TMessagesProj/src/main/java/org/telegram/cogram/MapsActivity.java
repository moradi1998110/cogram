package org.telegram.cogram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

/**
 * Phogram's Maps tab, in the slot where Telegram has Settings (Settings opens from the chats page's options
 * menu, as Telegram does when Calls holds this slot). No map is chosen yet, so the page shows an empty
 * state in Telegram's style.
 *
 * The page is ready for a map: {@link #setMapView} puts it under the glass header and tab bar, and
 * {@link #getMapPadding} tells it how much of each edge those bars cover, so the map keeps its logo and
 * controls visible. While a map is shown, swipes on it move the map instead of switching tabs.
 */
public class MapsActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private FrameLayout contentView;
    private FrameLayout mapContainer;
    private LinearLayout emptyView;
    private ImageView emptyIcon;
    private TextView emptyTitle;
    private TextView emptyText;
    private View mapView;

    private boolean hasMainTabs;
    private int statusBarHeight;
    private int navigationBarHeight;

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
        actionBar.setTitle(LocaleController.getString(R.string.PhogramMapsTab));
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
        emptyTitle.setText(LocaleController.getString(R.string.PhogramMapsTab));
        emptyView.addView(emptyTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 18, 0, 0));

        emptyText = new TextView(context);
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setText(LocaleController.getString(R.string.PhogramMapsEmpty));
        emptyView.addView(emptyText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));

        updateColors();
        ViewCompat.setOnApplyWindowInsetsListener(contentView, this::onApplyWindowInsets);
        return fragmentView = contentView;
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
        emptyView.setVisibility(view == null ? View.VISIBLE : View.GONE);
    }

    /** How much of the top and bottom edges the header and the tab bar cover, for the map's own padding. */
    public int[] getMapPadding() {
        final int top = statusBarHeight + ActionBar.getCurrentActionBarHeight();
        final int bottom = navigationBarHeight + (hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0);
        return new int[] { 0, top, 0, bottom };
    }

    private void updateColors() {
        if (contentView == null) return;
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        emptyTitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        emptyText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
    }

    @Override
    public void onResume() {
        super.onResume();
        updateColors();
        // A map added later resumes here.
    }

    @Override
    public void onPause() {
        super.onPause();
        // A map added later pauses here, so it does not use the battery while another tab is open.
    }

    @Override
    public boolean isLightStatusBar() {
        return AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundGray)) > 0.721f;
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
        final int[] padding = getMapPadding();
        // The empty state centers in the part of the page the bars leave free.
        emptyView.setPadding(dp(32), padding[1], dp(32), padding[3]);
        return WindowInsetsCompat.CONSUMED;
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        // With a map shown, a swipe moves the map; without one the tabs slide as on any other page.
        return mapView == null;
    }
}
