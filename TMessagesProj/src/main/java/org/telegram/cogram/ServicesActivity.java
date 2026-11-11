package org.telegram.cogram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BottomPagesView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;

/**
 * Phogram's Services tab, shown in place of Telegram's Contacts tab. It is built only from Telegram's own
 * pieces: the settings page's gray background and glass header, rounded white cards, the colorful icon
 * gradients of Telegram's settings rows, and the page dots of the intro screen.
 *
 * This first version shows sample adverts and services; their content will come from the organization's
 * server later. Contacts stay reachable from the new message button on the chats page.
 */
public class ServicesActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private static final int ADS_HEIGHT_DP = 172;
    private static final int ADS_DOTS_DP = 22;
    private static final int TILE_HEIGHT_DP = 104;
    private static final int GRID_GAP_DP = 8;
    private static final int SIDE_DP = 12;
    private static final int COLUMNS = 3;
    private static final long ADS_ROTATE_MS = 5000;

    /** A sample advert until the organization's server supplies real ones. */
    private static final class Advert {
        final int title;
        final int text;
        final int colorTop;
        final int colorBottom;

        Advert(int title, int text, int colorTop, int colorBottom) {
            this.title = title;
            this.text = text;
            this.colorTop = colorTop;
            this.colorBottom = colorBottom;
        }
    }

    /** A sample service until the organization's server supplies real ones. */
    private static final class Service {
        final int name;
        final int icon;
        final int colorTop;
        final int colorBottom;

        Service(int name, int icon, int colorTop, int colorBottom) {
            this.name = name;
            this.icon = icon;
            this.colorTop = colorTop;
            this.colorBottom = colorBottom;
        }
    }

    // Colors are the gradient pairs of Telegram's own settings icons.
    private static final Advert[] SAMPLE_ADVERTS = {
        new Advert(R.string.PhogramAdSample1Title, R.string.PhogramAdSample1Text, 0xFF1BA4ED, 0xFF1488E1),
        new Advert(R.string.PhogramAdSample2Title, R.string.PhogramAdSample2Text, 0xFFB659FF, 0xFF617CFF),
        new Advert(R.string.PhogramAdSample3Title, R.string.PhogramAdSample3Text, 0xFFF38B31, 0xFFE26314),
    };

    private static final Service[] SAMPLE_SERVICES = {
        new Service(R.string.PhogramServiceSalifni, R.drawable.msg_payment_card, 0xFF55CA47, 0xFF27B434),
        new Service(R.string.PhogramServiceBousla, R.drawable.msg_location, 0xFF1BA4ED, 0xFF1488E1),
        new Service(R.string.PhogramServicePayments, R.drawable.msg_payment_delivery, 0xFFEFA612, 0xFFE77512),
        new Service(R.string.PhogramServiceAppointments, R.drawable.msg_calendar2, 0xFFF45255, 0xFFDF3955),
        new Service(R.string.PhogramServiceReports, R.drawable.msg_stats, 0xFFB659FF, 0xFF617CFF),
        new Service(R.string.PhogramServiceSupport, R.drawable.msg_help, 0xFFF38B31, 0xFFE26314),
    };

    private SizeNotifierFrameLayout contentView;
    private UniversalRecyclerView listView;
    private View actionBarBackground;
    private AdvertsView advertsView;
    private ServicesGridView servicesView;

    private boolean hasMainTabs;
    private int navigationBarHeight;
    private int additionNavigationBarHeight;

    private boolean actionBarBackgroundVisible;
    private ValueAnimator actionBarBackgroundAnimator;

    public ServicesActivity(Bundle args) {
        super(args);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scrollableViewNoiseSuppressor = new DownscaleScrollableNoiseSuppressor();
            iBlur3SourceGlassFrosted = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceGlass = new BlurredBackgroundSourceRenderNode(null);
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlassFrosted = null;
            iBlur3SourceGlass = null;
        }
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        }
        additionNavigationBarHeight = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        contentView = new SizeNotifierFrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (Build.VERSION.SDK_INT >= 31 && scrollableViewNoiseSuppressor != null) {
                    blur3_InvalidateBlur();
                    final int width = getMeasuredWidth();
                    final int height = getMeasuredHeight();
                    if (iBlur3SourceGlassFrosted != null && !iBlur3SourceGlassFrosted.inRecording()) {
                        final Canvas c = iBlur3SourceGlassFrosted.beginRecording(width, height);
                        c.drawColor(getThemedColor(Theme.key_windowBackgroundGray));
                        if (SharedConfig.chatBlurEnabled()) {
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_FROSTED_GLASS);
                        }
                        iBlur3SourceGlassFrosted.endRecording();
                    }
                    if (iBlur3SourceGlass != null && !iBlur3SourceGlass.inRecording()) {
                        final Canvas c = iBlur3SourceGlass.beginRecording(width, height);
                        c.drawColor(getThemedColor(Theme.key_windowBackgroundGray));
                        if (SharedConfig.chatBlurEnabled()) {
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                        }
                        iBlur3SourceGlass.endRecording();
                    }
                }
                super.dispatchDraw(canvas);
                if (!hasMainTabs) {
                    AndroidUtilities.drawNavigationBarProtection(canvas, this, getThemedColor(Theme.key_windowBackgroundGray), navigationBarHeight);
                }
            }

            @Override
            public void drawBlurRect(Canvas canvas, float y, Rect rectTmp, Paint blurScrimPaint, boolean top) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !SharedConfig.chatBlurEnabled() || iBlur3SourceGlassFrosted == null) {
                    canvas.drawRect(rectTmp, blurScrimPaint);
                    return;
                }
                canvas.save();
                canvas.translate(0, -y);
                iBlur3SourceGlassFrosted.draw(canvas, rectTmp.left, rectTmp.top + y, rectTmp.right, rectTmp.bottom + y);
                canvas.restore();

                final int oldScrimAlpha = blurScrimPaint.getAlpha();
                blurScrimPaint.setAlpha(ChatActivity.ACTION_BAR_BLUR_ALPHA);
                canvas.drawRect(rectTmp, blurScrimPaint);
                blurScrimPaint.setAlpha(oldScrimAlpha);
            }

            @Override
            public void updateColors() {
                super.updateColors();
                ServicesActivity.this.updateColors();
            }
        };

        if (!hasMainTabs) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        }
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.PhogramServicesTab));
        actionBar.setActionBarMenuOnItemClick(new org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
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

        advertsView = new AdvertsView(context);
        servicesView = new ServicesGridView(context);

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.statusBarHeight + org.telegram.ui.ActionBar.ActionBar.getCurrentActionBarHeight(), 0, AndroidUtilities.navigationBarHeight + additionNavigationBarHeight);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateActionBarBackground(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                }
            }
        });
        iBlur3Capture = new ViewGroupPartRenderer(listView, contentView, listView::drawChild);
        listView.addEdgeEffectListener(() -> listView.postOnAnimation(this::blur3_InvalidateBlur));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        actionBarBackground = new View(context) {
            private final Paint blurScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                final int top = actionBar.getHeight();
                AndroidUtilities.rectTmp2.set(0, 0, getMeasuredWidth(), top);
                blurScrimPaint.setColor(Theme.getColor(Theme.key_actionBarDefault, resourceProvider));
                contentView.drawBlurRect(canvas, 0, AndroidUtilities.rectTmp2, blurScrimPaint, true);
                if (getParentLayout() != null) {
                    getParentLayout().drawHeaderShadow(canvas, top);
                }
            }
        };
        actionBarBackground.setAlpha(0f);
        contentView.addView(actionBarBackground, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 200, Gravity.TOP));
        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));

        listView.adapter.update(false);
        updateColors();

        ViewCompat.setOnApplyWindowInsetsListener(contentView, this::onApplyWindowInsets);
        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCustomShadow(advertsView, ADS_HEIGHT_DP + ADS_DOTS_DP));
        items.add(UItem.asCustomShadow(servicesView, servicesView.heightDp()));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        // The cards handle their own taps.
    }

    private void openService(Service service) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.PhogramServiceNotReady)).show();
    }

    private void updateActionBarBackground(boolean animated) {
        boolean visible = false;
        if (listView.getChildCount() > 0) {
            final View first = listView.getChildAt(0);
            visible = listView.getChildAdapterPosition(first) > 0 || first.getY() < listView.getPaddingTop();
        }
        if (visible == actionBarBackgroundVisible) return;
        actionBarBackgroundVisible = visible;
        if (actionBarBackgroundAnimator != null) {
            actionBarBackgroundAnimator.cancel();
            actionBarBackgroundAnimator = null;
        }
        if (!animated) {
            actionBarBackground.setAlpha(visible ? 1f : 0f);
            return;
        }
        actionBarBackgroundAnimator = ValueAnimator.ofFloat(actionBarBackground.getAlpha(), visible ? 1f : 0f);
        actionBarBackgroundAnimator.addUpdateListener(a -> actionBarBackground.setAlpha((float) a.getAnimatedValue()));
        actionBarBackgroundAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        actionBarBackgroundAnimator.setDuration(420);
        actionBarBackgroundAnimator.start();
    }

    private void updateColors() {
        if (contentView == null) return;
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        if (advertsView != null) advertsView.updateColors();
        if (servicesView != null) servicesView.updateColors();
        if (actionBarBackground != null) actionBarBackground.invalidate();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateColors();
        if (advertsView != null) advertsView.scheduleRotation();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (advertsView != null) advertsView.cancelRotation();
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
        navigationBarHeight = systemInsets.bottom;
        listView.setPadding(0, systemInsets.top + org.telegram.ui.ActionBar.ActionBar.getCurrentActionBarHeight(), 0, navigationBarHeight + additionNavigationBarHeight);
        return WindowInsetsCompat.CONSUMED;
    }

    /* Adverts: rounded cards that slide by themselves, with the page dots of Telegram's intro screen. */

    private class AdvertsView extends FrameLayout {

        private final ViewPager pager;
        private final BottomPagesView dots;
        private final Runnable rotate = this::showNextAdvert;
        private boolean userDragging;

        AdvertsView(Context context) {
            super(context);
            pager = new ViewPager(context);
            pager.setClipToPadding(false);
            pager.setPadding(dp(SIDE_DP), 0, dp(SIDE_DP), 0);
            pager.setPageMargin(dp(GRID_GAP_DP));
            pager.setAdapter(new PagerAdapter() {
                @Override
                public int getCount() {
                    return SAMPLE_ADVERTS.length;
                }

                @NonNull
                @Override
                public Object instantiateItem(@NonNull ViewGroup container, int position) {
                    final View card = createAdvertCard(container.getContext(), SAMPLE_ADVERTS[position]);
                    container.addView(card);
                    return card;
                }

                @Override
                public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                    container.removeView((View) object);
                }

                @Override
                public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                    return view == object;
                }
            });
            addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, ADS_HEIGHT_DP, Gravity.TOP, 0, 8, 0, 0));

            dots = new BottomPagesView(context, pager, SAMPLE_ADVERTS.length);
            dots.setColor(Theme.key_chats_unreadCounterMuted, Theme.key_chats_actionBackground);
            addView(dots, LayoutHelper.createFrame(11 * SAMPLE_ADVERTS.length, 5, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, ADS_HEIGHT_DP + 8 + 6, 0, 0));

            pager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                    dots.setPageOffset(position, positionOffset);
                }

                @Override
                public void onPageSelected(int position) {
                    dots.setCurrentPage(position);
                }

                @Override
                public void onPageScrollStateChanged(int state) {
                    userDragging = state == ViewPager.SCROLL_STATE_DRAGGING;
                    if (userDragging) {
                        cancelRotation();
                    } else if (state == ViewPager.SCROLL_STATE_IDLE) {
                        scheduleRotation();
                    }
                }
            });
        }

        private View createAdvertCard(Context context, Advert advert) {
            final LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.BOTTOM);
            card.setPadding(dp(18), dp(16), dp(18), dp(18));
            final GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { advert.colorTop, advert.colorBottom });
            background.setCornerRadius(dp(16));
            card.setBackground(background);

            final int gravity = (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);

            final TextView title = new TextView(context);
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            title.setTypeface(AndroidUtilities.bold());
            title.setGravity(gravity);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setText(LocaleController.getString(advert.title));
            card.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final TextView text = new TextView(context);
            text.setTextColor(0xD9FFFFFF);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            text.setGravity(gravity);
            text.setMaxLines(2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setText(LocaleController.getString(advert.text));
            card.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            card.setOnClickListener(v -> BulletinFactory.of(ServicesActivity.this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.PhogramServiceNotReady)).show());
            ScaleStateListAnimator.apply(card, 0.02f, 1.5f);
            return card;
        }

        void scheduleRotation() {
            cancelRotation();
            if (!userDragging && SAMPLE_ADVERTS.length > 1 && LiteMode.isEnabled(LiteMode.FLAGS_ANIMATED_STICKERS)) {
                AndroidUtilities.runOnUIThread(rotate, ADS_ROTATE_MS);
            }
        }

        void cancelRotation() {
            AndroidUtilities.cancelRunOnUIThread(rotate);
        }

        private void showNextAdvert() {
            if (!isAttachedToWindow()) return;
            pager.setCurrentItem((pager.getCurrentItem() + 1) % SAMPLE_ADVERTS.length, true);
            scheduleRotation();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            scheduleRotation();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            cancelRotation();
        }

        void updateColors() {
            dots.invalidate();
        }
    }

    /* Services: a grid of rounded white cards, each with a colorful icon like Telegram's settings rows. */

    private class ServicesGridView extends ViewGroup {

        private final ArrayList<LinearLayout> tiles = new ArrayList<>();
        private final ArrayList<TextView> names = new ArrayList<>();

        ServicesGridView(Context context) {
            super(context);
            for (final Service service : SAMPLE_SERVICES) {
                final LinearLayout tile = new LinearLayout(context);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(6), dp(8), dp(6), dp(8));

                final ImageView icon = new ImageView(context);
                icon.setScaleType(ImageView.ScaleType.CENTER);
                icon.setImageResource(service.icon);
                icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
                final GradientDrawable iconBackground = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { service.colorTop, service.colorBottom });
                iconBackground.setCornerRadius(dp(12));
                icon.setBackground(iconBackground);
                tile.addView(icon, LayoutHelper.createLinear(44, 44, Gravity.CENTER_HORIZONTAL));

                final TextView name = new TextView(context);
                name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                name.setGravity(Gravity.CENTER);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setText(LocaleController.getString(service.name));
                tile.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

                tile.setOnClickListener(v -> openService(service));
                ScaleStateListAnimator.apply(tile);
                tiles.add(tile);
                names.add(name);
                addView(tile);
            }
            updateColors();
        }

        int rows() {
            return (tiles.size() + COLUMNS - 1) / COLUMNS;
        }

        int heightDp() {
            final int rows = rows();
            return rows == 0 ? 0 : 8 + rows * TILE_HEIGHT_DP + (rows - 1) * GRID_GAP_DP + 8;
        }

        private int tileWidth(int width) {
            return (width - dp(SIDE_DP) * 2 - dp(GRID_GAP_DP) * (COLUMNS - 1)) / COLUMNS;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int tileWidth = tileWidth(width);
            for (final View tile : tiles) {
                tile.measure(MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(TILE_HEIGHT_DP), MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(width, dp(heightDp()));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int width = r - l;
            final int tileWidth = tileWidth(width);
            for (int i = 0; i < tiles.size(); i++) {
                final int row = i / COLUMNS;
                final int column = i % COLUMNS;
                final int x = LocaleController.isRTL
                    ? width - dp(SIDE_DP) - (column + 1) * tileWidth - column * dp(GRID_GAP_DP)
                    : dp(SIDE_DP) + column * (tileWidth + dp(GRID_GAP_DP));
                final int y = dp(8) + row * (dp(TILE_HEIGHT_DP) + dp(GRID_GAP_DP));
                tiles.get(i).layout(x, y, x + tileWidth, y + dp(TILE_HEIGHT_DP));
            }
        }

        void updateColors() {
            final int background = getThemedColor(Theme.key_windowBackgroundWhite);
            final int pressed = getThemedColor(Theme.key_listSelector);
            for (final View tile : tiles) {
                tile.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), background, pressed));
            }
            for (final TextView name : names) {
                name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            }
        }
    }

    /* Blur, as on Telegram's settings page, so the glass header and tab bar show this page through them. */

    private final DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;
    private final BlurredBackgroundSourceRenderNode iBlur3SourceGlassFrosted;
    private final BlurredBackgroundSourceRenderNode iBlur3SourceGlass;
    private IBlur3Capture iBlur3Capture;

    private final ArrayList<android.graphics.RectF> iBlur3Positions = new ArrayList<>();
    private final android.graphics.RectF iBlur3PositionActionBar = new android.graphics.RectF();
    private final android.graphics.RectF iBlur3PositionMainTabs = new android.graphics.RectF();
    {
        iBlur3Positions.add(iBlur3PositionActionBar);
        iBlur3Positions.add(iBlur3PositionMainTabs);
    }

    private void blur3_InvalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || scrollableViewNoiseSuppressor == null || fragmentView == null) {
            return;
        }
        final int additionalList = dp(48);
        final int mainTabBottom = fragmentView.getMeasuredHeight() - navigationBarHeight - dp(DialogsActivity.MAIN_TABS_MARGIN);
        final int mainTabTop = mainTabBottom - dp(DialogsActivity.MAIN_TABS_HEIGHT);

        iBlur3PositionActionBar.set(0, -additionalList, fragmentView.getMeasuredWidth(), actionBar.getMeasuredHeight() + additionalList);
        iBlur3PositionMainTabs.set(0, mainTabTop, fragmentView.getMeasuredWidth(), mainTabBottom);
        iBlur3PositionMainTabs.inset(0, LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0 : -dp(48));

        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, hasMainTabs ? 2 : 1);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(iBlur3Capture, fragmentView.getMeasuredWidth(), fragmentView.getMeasuredHeight());
    }

    @Override
    public BlurredBackgroundSourceRenderNode getGlassSource() {
        return iBlur3SourceGlass;
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        // A swipe on the adverts turns their pages instead of switching tabs.
        if (advertsView != null && advertsView.isAttachedToWindow()) {
            final int[] location = new int[2];
            advertsView.getLocationOnScreen(location);
            final float x = ev.getRawX();
            final float y = ev.getRawY();
            if (x >= location[0] && x < location[0] + advertsView.getWidth() && y >= location[1] && y < location[1] + advertsView.getHeight()) {
                return false;
            }
        }
        return true;
    }
}
