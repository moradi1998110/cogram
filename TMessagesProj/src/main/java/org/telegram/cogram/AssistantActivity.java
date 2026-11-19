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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.Bulletin;
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
 * Phogram's AI assistant tab, in the slot where Telegram has the Profile tab (the profile and the accounts open
 * from the chats page's options menu). Built only from Telegram's own pieces, like the settings page: the gray
 * background and glass header, a top card, white grouped rows with icons, section headers and footnotes.
 *
 * This first version is the home page only: a greeting, a button that starts a conversation, suggested
 * requests and the list of past conversations. The conversations themselves open in Telegram's own chat
 * screen with the assistant, which comes in the next step together with the organization's AI server.
 */
public class AssistantActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private static final int ID_SUGGEST_WRITE = 1;
    private static final int ID_SUGGEST_SUMMARY = 2;
    private static final int ID_SUGGEST_TRANSLATE = 3;
    private static final int ID_SUGGEST_EXPLAIN = 4;
    private static final int ID_SUGGEST_PHOTO = 5;

    private SizeNotifierFrameLayout contentView;
    private UniversalRecyclerView listView;
    private View actionBarBackground;
    private HeroView heroView;

    private boolean hasMainTabs;
    private int navigationBarHeight;
    private int additionNavigationBarHeight;

    private boolean actionBarBackgroundVisible;
    private ValueAnimator actionBarBackgroundAnimator;

    public AssistantActivity(Bundle args) {
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
                AssistantActivity.this.updateColors();
            }
        };

        if (!hasMainTabs) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        }
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.PhogramAssistantTab));
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

        heroView = new HeroView(context);

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight(), 0, AndroidUtilities.navigationBarHeight + additionNavigationBarHeight);
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
        items.add(UItem.asCustomShadow(heroView, HeroView.HEIGHT_DP));
        items.add(UItem.asHeader(LocaleController.getString(R.string.PhogramAssistantSuggestions)));
        items.add(UItem.asButton(ID_SUGGEST_WRITE, R.drawable.msg_edit, LocaleController.getString(R.string.PhogramAssistantSuggestWrite)));
        items.add(UItem.asButton(ID_SUGGEST_SUMMARY, R.drawable.msg_list, LocaleController.getString(R.string.PhogramAssistantSuggestSummary)));
        items.add(UItem.asButton(ID_SUGGEST_TRANSLATE, R.drawable.msg_translate, LocaleController.getString(R.string.PhogramAssistantSuggestTranslate)));
        items.add(UItem.asButton(ID_SUGGEST_EXPLAIN, R.drawable.msg_help, LocaleController.getString(R.string.PhogramAssistantSuggestExplain)));
        items.add(UItem.asButton(ID_SUGGEST_PHOTO, R.drawable.msg_photos, LocaleController.getString(R.string.PhogramAssistantSuggestPhoto)));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.PhogramAssistantRecent)));
        recent.clear();
        final AssistantBot bot = CogramGateway.getInstance(currentAccount).assistant();
        if (bot != null) {
            recent.addAll(bot.recentTopics());
        }
        if (recent.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PhogramAssistantNoChats)));
        } else {
            for (int i = 0; i < recent.size() && i < MAX_RECENT; i++) {
                final TLRPC.TL_forumTopic topic = recent.get(i);
                items.add(UItem.asButton(ID_RECENT_BASE + i, R.drawable.msg_discussion, topic.title, LocaleController.formatDateChat(topic.date)));
            }
            items.add(UItem.asShadow(null));
        }
    }

    private static final int ID_RECENT_BASE = 100;
    private static final int MAX_RECENT = 20;
    private final ArrayList<TLRPC.TL_forumTopic> recent = new ArrayList<>();

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_SUGGEST_WRITE && item.id <= ID_SUGGEST_PHOTO) {
            startConversation(suggestionText(item.id));
        } else if (item.id >= ID_RECENT_BASE && item.id - ID_RECENT_BASE < recent.size()) {
            openConversation(recent.get(item.id - ID_RECENT_BASE).id);
        }
    }

    private String suggestionText(int id) {
        switch (id) {
            case ID_SUGGEST_WRITE: return LocaleController.getString(R.string.PhogramAssistantSuggestWrite);
            case ID_SUGGEST_SUMMARY: return LocaleController.getString(R.string.PhogramAssistantSuggestSummary);
            case ID_SUGGEST_TRANSLATE: return LocaleController.getString(R.string.PhogramAssistantSuggestTranslate);
            case ID_SUGGEST_EXPLAIN: return LocaleController.getString(R.string.PhogramAssistantSuggestExplain);
            case ID_SUGGEST_PHOTO: return LocaleController.getString(R.string.PhogramAssistantSuggestPhoto);
        }
        return null;
    }

    /**
     * Opens Telegram's chat with the assistant, which shows its conversations as topics; a new message there starts
     * a new conversation. A suggestion is left in the message field as a draft for the user to finish.
     */
    private void startConversation(String suggestion) {
        final AssistantBot bot = CogramGateway.getInstance(currentAccount).assistant();
        if (bot == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.PhogramAssistantNotReady)).show();
            return;
        }
        getMessagesController().putUser(bot.user(), false);
        if (suggestion != null) {
            getMediaDataController().saveDraft(AssistantBot.USER_ID, 0, suggestion + " ", null, null, null, null, 0, false, false);
        }
        presentFragment(ChatActivity.of(AssistantBot.USER_ID));
    }

    /** Opens one past conversation: the assistant's chat on that topic, once Telegram knows the topic. */
    private void openConversation(int topicId) {
        final AssistantBot bot = CogramGateway.getInstance(currentAccount).assistant();
        if (bot == null) return;
        getMessagesController().putUser(bot.user(), false);
        final ChatActivity chat = ChatActivity.of(AssistantBot.USER_ID);
        if (getMessagesController().getTopicsController().findTopic(-AssistantBot.USER_ID, topicId) != null) {
            ForumUtilities.applyTopic(chat, MessagesStorage.TopicKey.of(AssistantBot.USER_ID, topicId));
        }
        presentFragment(chat);
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
        if (heroView != null) heroView.updateColors();
        if (actionBarBackground != null) actionBarBackground.invalidate();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateColors();
        // A conversation may have started or changed in the chat screen.
        if (listView != null) {
            listView.adapter.update(false);
        }
        // Bulletins rise above the floating tab bar, as on Telegram's chats page.
        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return navigationBarHeight + additionNavigationBarHeight;
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        Bulletin.removeDelegate(this);
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
        listView.setPadding(0, systemInsets.top + ActionBar.getCurrentActionBarHeight(), 0, navigationBarHeight + additionNavigationBarHeight);
        return WindowInsetsCompat.CONSUMED;
    }

    /*
     * The top card: Telegram's AI star in a gradient circle (the purple-blue pair of Telegram's settings icons),
     * a greeting, a line on what the assistant does, and Telegram's blue button that starts a conversation.
     */
    private class HeroView extends LinearLayout {

        static final int HEIGHT_DP = 262;

        private final TextView title;
        private final TextView text;
        private final TextView button;

        HeroView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(32), dp(18), dp(32), dp(12));

            final ImageView icon = new ImageView(context);
            // The icon is drawn larger inside the circle; scaling the view would scale the circle out of the card.
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setPadding(dp(22), dp(22), dp(22), dp(22));
            icon.setImageResource(R.drawable.input_ai);
            icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            final GradientDrawable circle = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFFB659FF, 0xFF617CFF });
            circle.setShape(GradientDrawable.OVAL);
            icon.setBackground(circle);
            addView(icon, LayoutHelper.createLinear(84, 84, Gravity.CENTER_HORIZONTAL));

            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            title.setTypeface(AndroidUtilities.bold());
            title.setGravity(Gravity.CENTER);
            title.setText(LocaleController.getString(R.string.PhogramAssistantGreeting));
            addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            text.setGravity(Gravity.CENTER);
            text.setText(LocaleController.getString(R.string.PhogramAssistantIntro));
            addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0));

            button = new TextView(context);
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            button.setTypeface(AndroidUtilities.bold());
            button.setGravity(Gravity.CENTER);
            button.setText(LocaleController.getString(R.string.PhogramAssistantNewChat));
            ScaleStateListAnimator.apply(button, 0.02f, 1.2f);
            button.setOnClickListener(v -> startConversation(null));
            addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, Gravity.CENTER_HORIZONTAL, 0, 18, 0, 0));
        }

        void updateColors() {
            title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            text.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            button.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
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
        return true;
    }
}
