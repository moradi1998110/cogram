package org.telegram.cogram;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.ArrayList;
import java.util.List;

/**
 * Directions on the Maps tab, in Telegram's own pieces:
 * <ul>
 * <li>the preview: a card at the top with where from and where to, and a sheet at the bottom with the time, the
 * arrival, the road, the alternatives as Telegram's choice cards, the steps and the options, and Start;</li>
 * <li>the navigation: the next turn in a green strip at the top like Telegram's return-to-call bar, and at the
 * bottom the arrival, the time and distance left, Telegram's red End button and a progress line.</li>
 * </ul>
 * The map draws the routes and follows the driver (MapEngine); this keeps the page's part.
 */
final class MapsRouteUi {

    interface Host {
        MapEngine engine();

        BaseFragment fragment();

        /** The page's covered edges: status bar on top, tab bar at the bottom. */
        int[] padding();

        /** The mode changed, so the page shows or hides its own views and moves its round buttons. */
        void onModeChanged();

        void sendToChat(MapEngine.Place place);

        /** Asks for the location permission the way the location button does. */
        void askLocation();

        boolean hasLocationPermission();

        /** The trip ended (End, or arrived): the page closes the place's card too. */
        void onTripEnded();

        /** Sends the trip's message (where to, the arrival) into chats the user picks. */
        void shareTrip(String text);
    }

    static final int MODE_NONE = 0;
    static final int MODE_PREVIEW = 1;
    static final int MODE_NAVIGATION = 2;

    private final Host host;
    private int mode = MODE_NONE;
    private MapEngine.Place destination;
    private final List<MapEngine.Route> routes = new ArrayList<>();
    private int chosen;

    // The preview.
    private final FrameLayout topCard;
    private final ImageView topBack;
    private final TextView fromText;
    private final TextView toText;
    private final View fromDot;
    private final View toDot;
    private final LinearLayout sheet;
    private final TextView timeText;
    private final TextView statusText;
    private final TextView viaText;
    private final RadialProgressView progress;
    private final LinearLayout alternatives;
    private final TextView stepsButton;
    private final TextView startButton;
    private final TextView sendButton;

    // The navigation.
    private final FrameLayout banner;
    private final MapKinds.TurnView bannerTurn;
    private final Figure bannerDistance;
    private final TextView bannerStreet;
    private final LinearLayout bannerThen;
    private final TextView bannerThenText;
    private final MapKinds.TurnView bannerThenTurn;
    private final LinearLayout panel;
    private final Figure panelArrival;
    private final Figure panelTime;
    private final Figure panelDistance;
    private final TextView panelArrivalLabel;
    private final TextView panelTimeLabel;
    private final TextView panelDistanceLabel;
    private final TextView endButton;
    private final TripProgressView panelProgress;
    /** The first route's length: the whole trip, against which the bar measures, also after a new route. */
    private float tripMeters;
    private MapEngine.Route progressRoute;

    MapsRouteUi(Context context, FrameLayout parent, Host host) {
        this.host = host;
        final boolean rtl = LocaleController.isRTL;

        // The top card: back, then the two ends of the route with Telegram's small colored dots.
        topCard = new FrameLayout(context);
        topCard.setTranslationZ(dp(3));
        topCard.setVisibility(View.GONE);
        topCard.setClickable(true);
        topBack = new ImageView(context);
        topBack.setImageResource(R.drawable.ic_ab_back);
        topBack.setScaleType(ImageView.ScaleType.CENTER);
        topBack.setContentDescription(getString(R.string.AccDescrGoBack));
        topBack.setOnClickListener(v -> close());
        topCard.addView(topBack, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        final LinearLayout ends = new LinearLayout(context);
        ends.setOrientation(LinearLayout.VERTICAL);
        fromDot = new View(context);
        fromText = new TextView(context);
        ends.addView(endRow(context, fromDot, fromText), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));
        toDot = new View(context);
        toText = new TextView(context);
        ends.addView(endRow(context, toDot, toText), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));
        topCard.addView(ends, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 56, 6, 14, 6));
        parent.addView(topCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 8, 0, 8, 0));

        // The bottom sheet of the preview.
        sheet = new LinearLayout(context);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setTranslationZ(dp(4));
        sheet.setVisibility(View.GONE);
        sheet.setClickable(true);
        sheet.setPadding(dp(16), dp(14), dp(16), dp(12));
        final FrameLayout timeRow = new FrameLayout(context);
        timeText = new TextView(context);
        timeText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        timeText.setTypeface(AndroidUtilities.bold());
        timeRow.addView(timeText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL));
        progress = new RadialProgressView(context);
        progress.setSize(dp(22));
        timeRow.addView(progress, LayoutHelper.createFrame(32, 32, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));
        sheet.addView(timeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        statusText = text(context, 15, rtl);
        sheet.addView(statusText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        viaText = text(context, 14, rtl);
        sheet.addView(viaText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        alternatives = new LinearLayout(context);
        alternatives.setOrientation(LinearLayout.HORIZONTAL);
        // Right to left in Arabic, Kurdish and Persian: the fastest route on the right.
        if (rtl) alternatives.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sheet.addView(alternatives, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        final LinearLayout options = new LinearLayout(context);
        options.setOrientation(LinearLayout.HORIZONTAL);
        stepsButton = chip(context, getString(R.string.PhogramNavSteps));
        stepsButton.setOnClickListener(v -> showSteps());
        options.addView(stepsButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
        sendButton = chip(context, getString(R.string.PhogramMapsSendToChat));
        sendButton.setOnClickListener(v -> {
            if (destination != null) host.sendToChat(destination);
        });
        options.addView(sendButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
        sheet.addView(options, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 12, 0, 0));
        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button(context, getString(R.string.PhogramNavStart));
        startButton.setOnClickListener(v -> startNavigation());
        buttons.addView(startButton, LayoutHelper.createLinear(0, 48, 1f));
        sheet.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 14, 0, 0));
        parent.addView(sheet, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        // The navigation strip at the top, in the green of Telegram's return-to-call bar.
        banner = new FrameLayout(context);
        banner.setTranslationZ(dp(4));
        banner.setVisibility(View.GONE);
        banner.setClickable(true);
        bannerTurn = new MapKinds.TurnView(context);
        banner.addView(bannerTurn, LayoutHelper.createFrame(52, 52, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, rtl ? 0 : 12, 12, rtl ? 12 : 0, 0));
        bannerDistance = new Figure(context, 28, rtl ? Gravity.RIGHT : Gravity.LEFT);

        banner.addView(bannerDistance, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.TOP, rtl ? 14 : 76, 8, rtl ? 76 : 14, 0));
        bannerStreet = text(context, 16, rtl);
        bannerStreet.setMaxLines(2);
        banner.addView(bannerStreet, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, rtl ? 14 : 76, 46, rtl ? 76 : 14, 12));
        bannerThen = new LinearLayout(context);
        bannerThen.setOrientation(LinearLayout.HORIZONTAL);
        bannerThen.setGravity(Gravity.CENTER_VERTICAL);
        bannerThenText = text(context, 14, rtl);
        bannerThenText.setText(getString(R.string.PhogramNavThen));
        bannerThenTurn = new MapKinds.TurnView(context);
        bannerThen.addView(bannerThenText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
        bannerThen.addView(bannerThenTurn, LayoutHelper.createLinear(20, 20));
        parent.addView(banner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 8, 0, 8, 0));
        parent.addView(bannerThen, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 30, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, rtl ? 0 : 20, 0, rtl ? 20 : 0, 0));
        bannerThen.setPadding(dp(10), 0, dp(10), 0);
        bannerThen.setVisibility(View.GONE);
        bannerThen.setTranslationZ(dp(3));

        // The navigation panel at the bottom.
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTranslationZ(dp(4));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        final LinearLayout figures = new LinearLayout(context);
        figures.setOrientation(LinearLayout.HORIZONTAL);
        figures.setGravity(Gravity.CENTER_VERTICAL);
        panelArrival = new Figure(context, 16, Gravity.CENTER_HORIZONTAL);
        panelArrivalLabel = text(context, 13, rtl);
        figures.addView(figure(context, panelArrival, panelArrivalLabel, getString(R.string.PhogramNavArrival)), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        panelTime = new Figure(context, 16, Gravity.CENTER_HORIZONTAL);
        panelTimeLabel = text(context, 13, rtl);
        figures.addView(figure(context, panelTime, panelTimeLabel, getString(R.string.PhogramNavTimeLeft)), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        panelDistance = new Figure(context, 16, Gravity.CENTER_HORIZONTAL);
        panelDistanceLabel = text(context, 13, rtl);
        figures.addView(figure(context, panelDistance, panelDistanceLabel, getString(R.string.PhogramNavDistanceLeft)), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        endButton = button(context, getString(R.string.PhogramNavEnd));
        endButton.setPadding(dp(18), 0, dp(18), 0);
        endButton.setOnClickListener(v -> endTrip());
        figures.addView(endButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44));
        panel.addView(figures, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 12));
        panelProgress = new TripProgressView(context);
        panel.addView(panelProgress, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 8));
        parent.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        createNavigationExtras(context, parent);

        // The page's round buttons stay above whichever panel is shown.
        final View.OnLayoutChangeListener resized = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) AndroidUtilities.runOnUIThread(host::onModeChanged);
        };
        sheet.addOnLayoutChangeListener(resized);
        panel.addOnLayoutChangeListener(resized);
        updateColors();
    }

    // While driving: the speed in Telegram's round ring with the limit sign, the road's name under it, the warning
    // ahead under the strip, the overview and re-center buttons, and the panel that opens for stops and sharing.

    private MapKinds.SpeedView speedView;
    private MapKinds.LimitView limitView;
    private TextView streetPill;
    private LinearLayout speedGroup;
    private TextView warningChip;
    private int warningChipType = -1;
    private ImageView overviewButton;
    private CountdownButton recenterButton;
    /** How long the map waits, after the driver last moved it, before it goes back to following them. */
    private static final int BACK_TO_DRIVING_SECONDS = 10;
    private LinearLayout expanded;
    private View panelHandle;
    private TextView destinationText;
    private LinearLayout stopTiles;
    private TextView alongStatus;
    private android.widget.ScrollView alongScroll;
    private LinearLayout alongList;
    /** The rows the list shows at most; the map shows all the places that fit. */
    private static final int ALONG_ROWS = 50;
    private LinearLayout shareRow;
    private TextView shareTitle;
    private TextView shareText;
    private ImageView shareIcon;
    private TextView continueButton;
    private int alongKind = MapEngine.KIND_OTHER;
    private static final int[] STOP_KINDS = { MapEngine.KIND_FUEL, MapEngine.KIND_FOOD, MapEngine.KIND_PHARMACY, MapEngine.KIND_MONEY };
    private static final int[] STOP_NAMES = { R.string.PhogramMapsFuel, R.string.PhogramMapsRestaurants, R.string.PhogramMapsPharmacies, R.string.PhogramMapsAtms };

    private void createNavigationExtras(Context context, FrameLayout parent) {
        final boolean rtl = LocaleController.isRTL;
        speedGroup = new LinearLayout(context);
        speedGroup.setOrientation(LinearLayout.VERTICAL);
        speedGroup.setGravity(Gravity.CENTER_HORIZONTAL);
        speedGroup.setVisibility(View.GONE);
        final FrameLayout gauges = new FrameLayout(context);
        speedView = new MapKinds.SpeedView(context);
        speedView.setTranslationZ(dp(3));
        // Big enough to read at a glance while driving; the limit sign sits on its upper edge.
        gauges.addView(speedView, LayoutHelper.createFrame(82, 82, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, 0));
        limitView = new MapKinds.LimitView(context);
        limitView.setTranslationZ(dp(4));
        limitView.setVisibility(View.GONE);
        gauges.addView(limitView, LayoutHelper.createFrame(38, 38, Gravity.LEFT | Gravity.TOP, 60, 0, 0, 0));
        speedGroup.addView(gauges, LayoutHelper.createLinear(104, 96));
        streetPill = new TextView(context);
        streetPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        streetPill.setTypeface(AndroidUtilities.bold());
        streetPill.setSingleLine(true);
        streetPill.setEllipsize(TextUtils.TruncateAt.END);
        streetPill.setMaxWidth(dp(200));
        streetPill.setPadding(dp(10), dp(4), dp(10), dp(5));
        streetPill.setTranslationZ(dp(3));
        speedGroup.addView(streetPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        parent.addView(speedGroup, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.BOTTOM, 12, 0, 12, 0));

        warningChip = new TextView(context);
        warningChip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        warningChip.setTypeface(AndroidUtilities.bold());
        warningChip.setSingleLine(true);
        warningChip.setPadding(dp(12), dp(6), dp(12), dp(7));
        warningChip.setTranslationZ(dp(3));
        warningChip.setVisibility(View.GONE);
        parent.addView(warningChip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, 12, 0, 12, 0));

        overviewButton = roundButton(context, R.drawable.msg_map, getString(R.string.PhogramNavOverview));
        overviewButton.setOnClickListener(v -> {
            final MapEngine engine = host.engine();
            if (engine != null) engine.showOverview();
            showBackToDriving();
        });
        parent.addView(overviewButton, LayoutHelper.createFrame(44, 44, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.BOTTOM, 12, 0, 12, 0));
        // The voice: on or off with one tap, as Telegram's mute button; its language in the trip panel.
        muteButton = roundButton(context, R.drawable.msg_unmute, getString(R.string.PhogramNavVoice));
        muteButton.setOnClickListener(v -> {
            final boolean muted = !VoiceGuide.isMuted();
            if (voice != null) {
                voice.setMuted(muted);
            } else {
                new VoiceGuide(v.getContext()).setMuted(muted);
            }
            updateMuteButton();
        });
        parent.addView(muteButton, LayoutHelper.createFrame(44, 44, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.BOTTOM, 12, 0, 12, 0));
        muteButton.setVisibility(View.GONE);
        recenterButton = styleRound(new CountdownButton(context), R.drawable.msg_current_location, getString(R.string.PhogramNavRecenter));
        recenterButton.setOnClickListener(v -> backToDriving());
        parent.addView(recenterButton, LayoutHelper.createFrame(44, 44, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.BOTTOM, 12, 0, 12, 0));
        overviewButton.setVisibility(View.GONE);
        recenterButton.setVisibility(View.GONE);

        // The panel opens, by its handle or a tap, onto the trip: where to, stops on the way, sharing, continue.
        panelHandle = new View(context);
        panel.addView(panelHandle, 0, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
        expanded = new LinearLayout(context);
        expanded.setOrientation(LinearLayout.VERTICAL);
        expanded.setVisibility(View.GONE);
        expanded.setPadding(dp(16), dp(10), dp(16), 0);
        destinationText = text(context, 16, rtl);
        destinationText.setTypeface(AndroidUtilities.bold());
        destinationText.setSingleLine(true);
        destinationText.setEllipsize(TextUtils.TruncateAt.END);
        expanded.addView(destinationText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextView stopsTitle = text(context, 14, rtl);
        stopsTitle.setText(getString(R.string.PhogramNavAddStop));
        stopsTitle.setTag("gray");
        expanded.addView(stopsTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 6));
        stopTiles = new LinearLayout(context);
        stopTiles.setOrientation(LinearLayout.HORIZONTAL);
        if (rtl) stopTiles.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (int i = 0; i < STOP_KINDS.length; i++) {
            final int kind = STOP_KINDS[i];
            final LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(0, dp(8), 0, dp(6));
            final MapKinds.IconView icon = new MapKinds.IconView(context);
            icon.setKind(kind);
            tile.addView(icon, LayoutHelper.createLinear(38, 38, Gravity.CENTER_HORIZONTAL));
            final TextView label = text(context, 12, false);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setText(getString(STOP_NAMES[i]));
            tile.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 4, 2, 0));
            tile.setOnClickListener(v -> showAlong(alongKind == kind ? MapEngine.KIND_OTHER : kind));
            ScaleStateListAnimator.apply(tile, 0.04f, 1.2f);
            stopTiles.addView(tile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, i > 0 ? 4 : 0, 0, i < STOP_KINDS.length - 1 ? 4 : 0, 0));
        }
        expanded.addView(stopTiles, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        // The places of the chosen kind on the way to the destination, nearest first, filled in as the search goes
        // along the route; a tap offers one as a stop.
        alongStatus = text(context, 13, rtl);
        alongStatus.setTag("gray");
        alongStatus.setVisibility(View.GONE);
        expanded.addView(alongStatus, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 10, 2, 2));
        alongScroll = new android.widget.ScrollView(context);
        alongScroll.setVerticalScrollBarEnabled(false);
        alongList = new LinearLayout(context);
        alongList.setOrientation(LinearLayout.VERTICAL);
        alongScroll.addView(alongList);
        alongScroll.setVisibility(View.GONE);
        expanded.addView(alongScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0));
        // Share the trip: Telegram's send arrow, and a message with the arrival into a chat.
        shareRow = new LinearLayout(context);
        shareRow.setOrientation(LinearLayout.HORIZONTAL);
        shareRow.setGravity(Gravity.CENTER_VERTICAL);
        shareRow.setPadding(dp(14), dp(10), dp(14), dp(10));
        shareIcon = new ImageView(context);
        shareIcon.setImageResource(R.drawable.msg_send);
        shareIcon.setScaleType(ImageView.ScaleType.CENTER);
        final LinearLayout shareTexts = new LinearLayout(context);
        shareTexts.setOrientation(LinearLayout.VERTICAL);
        shareTitle = text(context, 16, rtl);
        shareTitle.setTypeface(AndroidUtilities.bold());
        shareTitle.setText(getString(R.string.PhogramNavShareTrip));
        shareText = text(context, 13, rtl);
        shareText.setText(getString(R.string.PhogramNavShareTripText));
        shareTexts.addView(shareTitle);
        shareTexts.addView(shareText);
        if (rtl) {
            shareRow.addView(shareTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            shareRow.addView(shareIcon, LayoutHelper.createLinear(40, 40, 12, 0, 0, 0));
        } else {
            shareRow.addView(shareIcon, LayoutHelper.createLinear(40, 40, 0, 0, 12, 0));
            shareRow.addView(shareTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        }
        shareRow.setOnClickListener(v -> shareTrip());
        ScaleStateListAnimator.apply(shareRow, 0.02f, 1.2f);
        expanded.addView(shareRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
        voiceRow = text(context, 15, rtl);
        voiceRow.setPadding(dp(14), dp(12), dp(14), dp(12));
        voiceRow.setOnClickListener(v -> chooseVoice());
        ScaleStateListAnimator.apply(voiceRow, 0.02f, 1.2f);
        expanded.addView(voiceRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        continueButton = button(context, getString(R.string.PhogramNavContinue));
        continueButton.setOnClickListener(v -> setExpanded(false));
        expanded.addView(continueButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 14, 0, 4));
        panel.addView(expanded, 1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        panel.setOnClickListener(v -> setExpanded(expanded.getVisibility() != View.VISIBLE));
    }

    private VoiceGuide voice;
    private ImageView muteButton;
    private TextView voiceRow;

    private static String voiceName(String language) {
        switch (language) {
            case "en": return "English";
            case "fa": return "فارسی";
            default: return "العربية";
        }
    }

    /** The mute button shows the voice's state with Telegram's speaker icons; the panel's row its language. */
    private void updateMuteButton() {
        final boolean muted = VoiceGuide.isMuted();
        muteButton.setImageResource(muted ? R.drawable.msg_mute : R.drawable.msg_unmute);
        muteButton.setContentDescription(getString(muted ? R.string.PhogramNavVoiceOff : R.string.PhogramNavVoice));
        voiceRow.setText(LocaleController.formatString(R.string.PhogramNavVoiceLanguage, voiceName(VoiceGuide.language())));
    }

    /** Telegram's list of the guidance languages; the chosen one speaks at once so the driver hears it. */
    private void chooseVoice() {
        final Activity activity = host.fragment().getParentActivity();
        if (activity == null) return;
        final CharSequence[] names = new CharSequence[VoiceGuide.LANGUAGES.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = voiceName(VoiceGuide.LANGUAGES[i]);
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.PhogramNavVoiceTitle));
        builder.setItems(names, (dialog, which) -> {
            VoiceGuide.setLanguage(VoiceGuide.LANGUAGES[which]);
            updateMuteButton();
            if (voice != null) voice.say("continue");
        });
        host.fragment().showDialog(builder.create());
    }

    /** The map moved away from the driver: the way back shows and counts down, from the top on each new move. */
    private void showBackToDriving() {
        if (mode != MODE_NAVIGATION) return;
        recenterButton.setVisibility(View.VISIBLE);
        recenterButton.start(BACK_TO_DRIVING_SECONDS, this::backToDriving);
    }

    /** Back to following the driver, centered, tilted and turning with the road. */
    private void backToDriving() {
        recenterButton.stop();
        recenterButton.setVisibility(View.GONE);
        final MapEngine engine = host.engine();
        if (engine != null && mode == MODE_NAVIGATION) engine.recenter();
    }

    private static ImageView roundButton(Context context, int icon, String description) {
        return styleRound(new ImageView(context), icon, description);
    }

    private static <T extends ImageView> T styleRound(T button, int icon, String description) {
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setTranslationZ(dp(3));
        button.setOutlineProvider(org.telegram.messenger.utils.ViewOutlineProviderImpl.BOUNDS_OVAL);
        ScaleStateListAnimator.apply(button);
        return button;
    }

    private void setExpanded(boolean open) {
        expanded.setVisibility(open && mode == MODE_NAVIGATION ? View.VISIBLE : View.GONE);
        if (destination != null) {
            destinationText.setText(LocaleController.formatString(R.string.PhogramNavTo, destination.name));
        }
        styleStopTiles();
    }

    /** Places of the kind along the route ahead as pins on the road; the same tile again takes them off. */
    private void showAlong(int kind) {
        final MapEngine engine = host.engine();
        if (engine == null) return;
        alongKind = kind;
        styleStopTiles();
        bindAlong(new java.util.ArrayList<>(), kind == MapEngine.KIND_OTHER);
        engine.showAlongRoute(kind, (places, finished) -> {
            if (alongKind != kind || kind == MapEngine.KIND_OTHER) return;
            bindAlong(places, finished);
            if (finished && places.isEmpty()) {
                BulletinFactory.of(host.fragment()).createSimpleBulletin(R.raw.chats_infotip, getString(R.string.PhogramNavNoneAlong)).show();
            }
        });
    }

    /** The list of the places on the way: how many so far (or that the search still goes on), then the nearest. */
    private void bindAlong(java.util.List<MapEngine.AlongPlace> places, boolean finished) {
        if (alongList == null) return;
        alongList.removeAllViews();
        // While a kind is chosen its list takes the place of the sharing and voice rows, so the panel stays as tall as
        // before and its End stays on the screen; choosing the kind again brings them back.
        final boolean listing = alongKind != MapEngine.KIND_OTHER;
        if (shareRow != null) shareRow.setVisibility(listing ? View.GONE : View.VISIBLE);
        if (voiceRow != null) voiceRow.setVisibility(listing ? View.GONE : View.VISIBLE);
        if (!listing) {
            alongStatus.setVisibility(View.GONE);
            alongScroll.setVisibility(View.GONE);
            return;
        }
        alongStatus.setVisibility(View.VISIBLE);
        alongStatus.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        final String count = places.isEmpty() ? "" : LocaleController.formatString(R.string.PhogramNavFoundAlong, places.size());
        alongStatus.setText(finished ? (places.isEmpty() ? getString(R.string.PhogramNavNoneAlong) : count)
                : places.isEmpty() ? getString(R.string.PhogramNavAlongSearching)
                : LocaleController.formatString(R.string.PhogramNavAlongCount, places.size()) + " · " + getString(R.string.PhogramNavAlongSearching));
        final Context context = alongList.getContext();
        final boolean rtl = LocaleController.isRTL;
        final int rows = Math.min(places.size(), ALONG_ROWS);
        for (int i = 0; i < rows; i++) {
            final MapEngine.AlongPlace along = places.get(i);
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(7), dp(4), dp(7));
            if (rtl) row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            final MapKinds.IconView icon = new MapKinds.IconView(context);
            icon.setKind(along.place.kind);
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView name = text(context, 15, rtl);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(along.place.name);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            final TextView details = text(context, 13, rtl);
            details.setSingleLine(true);
            details.setEllipsize(TextUtils.TruncateAt.END);
            details.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            final String ahead = LocaleController.formatString(R.string.PhogramNavAhead, MapsFormat.distance(along.ahead));
            details.setText(TextUtils.isEmpty(along.place.type) || along.place.type.equals(along.place.name)
                    ? ahead : along.place.type + " · " + ahead);
            texts.addView(name);
            texts.addView(details);
            row.addView(icon, LayoutHelper.createLinear(36, 36, 0, 0, 12, 0));
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            row.setBackground(Theme.getSelectorDrawable(false));
            row.setOnClickListener(v -> offerStop(along.place));
            alongList.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        alongScroll.setVisibility(rows > 0 ? View.VISIBLE : View.GONE);
        // Two rows and half of the third, which shows there is more to scroll, so the panel keeps its End in view.
        final android.view.ViewGroup.LayoutParams params = alongScroll.getLayoutParams();
        params.height = rows == 0 ? 0 : rows <= 2 ? rows * dp(54) : dp(54 * 2 + 27);
        alongScroll.setLayoutParams(params);
    }

    /** A pin tapped on the road: Telegram's dialog offers it as a stop on the way. */
    void offerStop(MapEngine.Place place) {
        if (mode != MODE_NAVIGATION) return;
        final Activity activity = host.fragment().getParentActivity();
        if (activity == null) return;
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(place.name);
        builder.setMessage(getString(R.string.PhogramNavAddStopText));
        builder.setPositiveButton(getString(R.string.PhogramNavAddStopButton), (dialog, which) -> {
            final MapEngine engine = host.engine();
            if (engine == null) return;
            engine.addStop(place);
            alongKind = MapEngine.KIND_OTHER;
            bindAlong(new java.util.ArrayList<>(), true);
            styleStopTiles();
            BulletinFactory.of(host.fragment()).createSimpleBulletin(R.raw.contact_check,
                    LocaleController.formatString(R.string.PhogramNavStopAdded, place.name)).show();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        host.fragment().showDialog(builder.create());
    }

    private void shareTrip() {
        if (destination == null) return;
        final String text = LocaleController.formatString(R.string.PhogramNavShareMessage, destination.name, lastArrival);
        host.shareTrip(text);
    }

    private String lastArrival = "";
    private boolean tellingWarning;

    private static String warningName(int type) {
        switch (type) {
            case MapEngine.Warning.TRAFFIC_LIGHTS: return getString(R.string.PhogramNavWarnLights);
            case MapEngine.Warning.LEVEL_CROSSING: return getString(R.string.PhogramNavWarnRailway);
            case MapEngine.Warning.SPEED_CAMERA: return getString(R.string.PhogramNavWarnCamera);
            case MapEngine.Warning.STOP: return getString(R.string.PhogramNavWarnStop);
            case MapEngine.Warning.PEDESTRIANS: return getString(R.string.PhogramNavWarnPedestrians);
            default: return getString(R.string.PhogramNavWarnBump);
        }
    }

    /** The driving extras on each new position: speed and limit, road, warning ahead, and a stop reached. */
    private void updateDriving(MapEngine.NavigationState state) {
        speedView.set(state.speed, state.speedLimit);
        limitView.setLimit(Math.round(state.speedLimit));
        if (state.street.isEmpty()) {
            streetPill.setVisibility(View.GONE);
        } else {
            streetPill.setText(state.street);
            streetPill.setVisibility(View.VISIBLE);
        }
        if (state.warning != null) {
            // The same sign as on the road, beside the name and the distance.
            if (warningChipType != state.warning.type) {
                warningChipType = state.warning.type;
                final android.graphics.drawable.BitmapDrawable sign = new android.graphics.drawable.BitmapDrawable(
                        warningChip.getResources(), MapKinds.warningImage(state.warning.type));
                sign.setBounds(0, 0, dp(22), dp(22));
                warningChip.setCompoundDrawablesRelative(sign, null, null, null);
                warningChip.setCompoundDrawablePadding(dp(8));
                warningChip.setGravity(Gravity.CENTER_VERTICAL);
            }
            warningChip.setText(warningName(state.warning.type) + " · " + MapsFormat.distance(state.toWarning));
            warningChip.setVisibility(View.VISIBLE);
        } else {
            warningChip.setVisibility(View.GONE);
        }
        if (state.passedStop != null) {
            BulletinFactory.of(host.fragment()).createSimpleBulletin(R.raw.contact_check,
                    LocaleController.formatString(R.string.PhogramNavStopReached, state.passedStop.name)).show();
        }
        lastArrival = MapsFormat.arrival(state.remainingSeconds);
    }

    private void styleStopTiles() {
        if (stopTiles == null) return;
        for (int i = 0; i < stopTiles.getChildCount(); i++) {
            final LinearLayout tile = (LinearLayout) stopTiles.getChildAt(i);
            final boolean on = STOP_KINDS[i] == alongKind;
            final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            final GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(14));
            background.setColor(on ? (accent & 0x00ffffff) | 0x26000000 : Theme.getColor(Theme.key_windowBackgroundGray));
            if (on) background.setStroke(dp(1.5f), accent);
            tile.setBackground(background);
            ((TextView) tile.getChildAt(1)).setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
    }

    private void styleNavigationExtras() {
        final int white = Theme.getColor(Theme.key_windowBackgroundWhite);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        speedView.invalidate();
        final GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(12));
        pill.setColor(MapKinds.accent());
        streetPill.setBackground(pill);
        streetPill.setTextColor(MapKinds.onAccent());
        final GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(16));
        chip.setColor(white);
        chip.setStroke(dp(1.5f), 0xFFE53935);
        warningChip.setBackground(chip);
        warningChip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        for (ImageView button : new ImageView[] { overviewButton, recenterButton, muteButton }) {
            button.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(44), Theme.getColor(Theme.key_location_actionBackground), Theme.getColor(Theme.key_location_actionPressedBackground)));
            button.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_location_actionActiveIcon), PorterDuff.Mode.MULTIPLY));
        }
        recenterButton.setColors(Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_location_actionActiveIcon));
        final GradientDrawable handle = new GradientDrawable();
        handle.setCornerRadius(dp(2));
        handle.setColor(Theme.getColor(Theme.key_sheet_scrollUp));
        panelHandle.setBackground(handle);
        destinationText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        final GradientDrawable voiceBackground = new GradientDrawable();
        voiceBackground.setCornerRadius(dp(14));
        voiceBackground.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        voiceRow.setBackground(voiceBackground);
        voiceRow.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        updateMuteButton();
        for (int i = 0; i < expanded.getChildCount(); i++) {
            final View child = expanded.getChildAt(i);
            if ("gray".equals(child.getTag())) ((TextView) child).setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }
        final GradientDrawable share = new GradientDrawable();
        share.setCornerRadius(dp(14));
        share.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        shareRow.setBackground(share);
        shareIcon.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        shareTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        shareText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        continueButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        continueButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(10), accent, Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        styleStopTiles();
    }

    private static LinearLayout endRow(Context context, View dot, TextView text) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(dot, LayoutHelper.createLinear(10, 10, 0, 0, 12, 0));
        row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return row;
    }

    private static TextView text(Context context, int size, boolean rtl) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        return view;
    }

    private static TextView chip(Context context, String label) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setText(label);
        ScaleStateListAnimator.apply(view, 0.04f, 1.2f);
        return view;
    }

    private static TextView button(Context context, String label) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setText(label);
        ScaleStateListAnimator.apply(view, 0.02f, 1.2f);
        return view;
    }

    /**
     * A number of the trip in Telegram's animated text: when it changes only the characters that differ roll, down
     * as the number falls and up as it grows, as Telegram's counters.
     */
    private static AnimatedTextView number(Context context, int sizeDp) {
        final AnimatedTextView view = new AnimatedTextView(context, false, true, true);
        view.setTextSize(dp(sizeDp));
        view.setTypeface(AndroidUtilities.bold());
        view.setAnimationProperties(0.35f, 0, 320, org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT_QUINT);
        return view;
    }

    /**
     * A trip figure: its number in Telegram's rolling digits and its unit as ordinary text beside it, so Arabic keeps
     * "1.1 كم" and "5:25 م" in their order (MapsFormat's parts). The row takes the page's direction.
     */
    static final class Figure extends LinearLayout {

        final AnimatedTextView number;
        final TextView unit;

        Figure(Context context, int sizeDp, int gravity) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(gravity | Gravity.BOTTOM);
            number = number(context, sizeDp);
            unit = new TextView(context);
            unit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, Math.max(11, sizeDp * 0.62f));
            unit.setTypeface(AndroidUtilities.bold());
            unit.setSingleLine(true);
            // Telegram lays its rows out left to right in every language, so in Arabic the unit goes first, to the
            // number's left, and the figure reads from the right as Arabic text does ("25 د").
            final boolean rtl = org.telegram.messenger.LocaleController.isRTL;
            if (rtl) addView(unit, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 4, 0, 4, (int) (sizeDp * 0.12f)));
            addView(number, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
            if (!rtl) addView(unit, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 4, 0, 4, (int) (sizeDp * 0.12f)));
        }

        void set(String[] parts, boolean animated) {
            number.setText(parts[0], animated);
            unit.setText(parts.length > 1 ? parts[1] : "");
            unit.setVisibility(parts.length > 1 && parts[1].length() > 0 ? VISIBLE : GONE);
        }

        void clear() {
            number.setText("", false);
            unit.setText("");
        }

        void setTextColor(int color) {
            number.setTextColor(color);
            unit.setTextColor(color);
        }
    }

    private static LinearLayout figure(Context context, Figure value, TextView label, String name) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        value.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        label.setGravity(Gravity.CENTER);
        label.setText(name);
        column.addView(value, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 28));
        column.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return column;
    }

    int getMode() {
        return mode;
    }

    /** The height the shown bottom panel takes, for the page's round buttons. */
    int bottomHeight() {
        if (mode == MODE_PREVIEW) return sheet.getHeight();
        if (mode == MODE_NAVIGATION) return panel.getHeight();
        return 0;
    }

    // The preview.

    /** Opens the preview of the ways to this place. */
    void preview(MapEngine.Place place) {
        if (!host.hasLocationPermission()) {
            host.askLocation();
            return;
        }
        destination = place;
        mode = MODE_PREVIEW;
        fromText.setText(getString(R.string.PhogramNavFromHere));
        toText.setText(place.name);
        routes.clear();
        chosen = 0;
        updateMode();
        findRoutes();
    }

    private void findRoutes() {
        final MapEngine engine = host.engine();
        if (engine == null || destination == null) return;
        final MapEngine.Place place = destination;
        showLoading(getString(R.string.PhogramMapsRouting));
        engine.showRoutes(place, false, (found, error) -> {
            if (destination != place || mode != MODE_PREVIEW) return;
            progress.setVisibility(View.GONE);
            routes.clear();
            routes.addAll(found);
            chosen = 0;
            if (found.isEmpty()) {
                timeText.setText(getString(R.string.PhogramNavNoRouteTitle));
                statusText.setText(getString(error == MapEngine.ROUTE_NO_DATA ? R.string.PhogramMapsRouteNeedsData
                        : error == MapEngine.ROUTE_NO_LOCATION ? R.string.PhogramMapsRouteNeedsLocation : R.string.PhogramMapsNoRoute));
                viaText.setVisibility(View.GONE);
                alternatives.setVisibility(View.GONE);
                startButton.setEnabled(false);
                startButton.setAlpha(0.5f);
                stepsButton.setEnabled(false);
                stepsButton.setAlpha(0.5f);
                return;
            }
            startButton.setEnabled(true);
            startButton.setAlpha(1f);
            stepsButton.setEnabled(true);
            stepsButton.setAlpha(1f);
            showChosen();
            buildAlternatives();
        });
    }

    private void showLoading(String text) {
        progress.setVisibility(View.VISIBLE);
        timeText.setText("");
        statusText.setText(text);
        viaText.setVisibility(View.GONE);
        alternatives.setVisibility(View.GONE);
        startButton.setEnabled(false);
        startButton.setAlpha(0.5f);
    }

    private void showChosen() {
        if (chosen >= routes.size()) return;
        final MapEngine.Route route = routes.get(chosen);
        timeText.setText(MapsFormat.duration(route.seconds));
        statusText.setText(LocaleController.formatString(R.string.PhogramNavArrivalLine, MapsFormat.arrival(route.seconds), MapsFormat.distance(route.meters)));
        if (route.via.isEmpty()) {
            viaText.setVisibility(View.GONE);
        } else {
            viaText.setText(LocaleController.formatString(R.string.PhogramNavVia, route.via));
            viaText.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Telegram's choice cards, one per route, each bordered in its route's color on the map: a dot and the time, what
     * kind of route it is (the fastest, the shortest, another way), how much slower than the fastest and how long.
     */
    private void buildAlternatives() {
        alternatives.removeAllViews();
        if (routes.size() < 2) {
            alternatives.setVisibility(View.GONE);
            return;
        }
        alternatives.setVisibility(View.VISIBLE);
        final Context context = alternatives.getContext();
        final boolean rtl = LocaleController.isRTL;
        final float best = routes.get(0).seconds;
        for (int i = 0; i < routes.size(); i++) {
            final MapEngine.Route route = routes.get(i);
            final LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(9), dp(10), dp(9));
            final LinearLayout top = new LinearLayout(context);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT));
            final View dot = new View(context);
            dot(dot, MapKinds.routeColor(route.index));
            final TextView time = text(context, 17, rtl);
            time.setTypeface(AndroidUtilities.bold());
            time.setSingleLine(true);
            time.setText(MapsFormat.duration(route.seconds));
            top.addView(dot, LayoutHelper.createLinear(9, 9, 0, 0, 6, 0));
            top.addView(time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            final TextView kind = text(context, 13, rtl);
            kind.setSingleLine(true);
            kind.setEllipsize(TextUtils.TruncateAt.END);
            kind.setTypeface(AndroidUtilities.bold());
            kind.setText(getString(route.label == MapEngine.Route.LABEL_FASTEST ? R.string.PhogramNavFastestRoute
                    : route.label == MapEngine.Route.LABEL_SHORTEST ? R.string.PhogramNavShortestRoute : R.string.PhogramNavOtherRoute));
            final TextView note = text(context, 13, rtl);
            note.setSingleLine(true);
            note.setEllipsize(TextUtils.TruncateAt.END);
            final float slower = route.seconds - best;
            note.setText(slower >= 60
                    ? LocaleController.formatString(R.string.PhogramNavSlowerDistance, MapsFormat.duration(slower), MapsFormat.distance(route.meters))
                    : MapsFormat.distance(route.meters));
            card.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            card.addView(kind, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
            card.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
            final int index = i;
            card.setOnClickListener(v -> selectRoute(index));
            ScaleStateListAnimator.apply(card, 0.02f, 1.2f);
            alternatives.addView(card, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, i > 0 ? 4 : 0, 0, i < routes.size() - 1 ? 4 : 0, 0));
        }
        styleAlternatives();
    }

    /** Chooses one of the routes, from its card or from its line on the map. */
    void selectRoute(int index) {
        if (mode != MODE_PREVIEW || index < 0 || index >= routes.size()) return;
        chosen = index;
        final MapEngine engine = host.engine();
        if (engine != null) engine.selectRoute(index);
        showChosen();
        styleAlternatives();
    }

    private void styleAlternatives() {
        for (int i = 0; i < alternatives.getChildCount() && i < routes.size(); i++) {
            final View card = alternatives.getChildAt(i);
            final int color = MapKinds.routeColor(routes.get(i).index);
            final boolean selected = i == chosen;
            final GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(14));
            // The chosen card is tinted with its route's color and bordered more strongly.
            background.setColor(selected ? (color & 0x00ffffff) | 0x1F000000 : Theme.getColor(Theme.key_windowBackgroundWhite));
            background.setStroke(dp(selected ? 2 : 1), selected ? color : (color & 0x00ffffff) | 0x80000000);
            card.setBackground(background);
            final LinearLayout column = (LinearLayout) card;
            final LinearLayout top = (LinearLayout) column.getChildAt(0);
            ((TextView) top.getChildAt(1)).setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            ((TextView) column.getChildAt(1)).setTextColor(color);
            ((TextView) column.getChildAt(2)).setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }
    }

    /** The turns of the chosen route, as a list in Telegram's dialog. */
    private void showSteps() {
        if (chosen >= routes.size()) return;
        final BaseFragment fragment = host.fragment();
        final Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        final MapEngine.Route route = routes.get(chosen);
        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        final boolean rtl = LocaleController.isRTL;
        for (MapEngine.Maneuver maneuver : route.maneuvers) {
            final FrameLayout row = new FrameLayout(activity);
            final MapKinds.TurnView turn = new MapKinds.TurnView(activity);
            turn.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            turn.setTurn(maneuver);
            row.addView(turn, LayoutHelper.createFrame(28, 28, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, rtl ? 0 : 22, 0, rtl ? 22 : 0, 0));
            final TextView words = text(activity, 15, rtl);
            words.setText(MapKinds.describe(maneuver));
            words.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            row.addView(words, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, rtl ? 22 : 66, 10, rtl ? 66 : 22, 0));
            final TextView at = text(activity, 13, rtl);
            at.setText(MapsFormat.distance(maneuver.distance));
            at.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            row.addView(at, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, rtl ? 22 : 66, 34, rtl ? 66 : 22, 10));
            list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        final ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.PhogramNavSteps));
        builder.setView(scroll);
        builder.setPositiveButton(getString(R.string.Close), null);
        fragment.showDialog(builder.create());
    }

    // The navigation.

    private void startNavigation() {
        final MapEngine engine = host.engine();
        if (engine == null || chosen >= routes.size()) return;
        mode = MODE_NAVIGATION;
        updateMode();
        bannerDistance.clear();
        bannerStreet.setText(getString(R.string.PhogramNavStarting));
        setKeepScreenOn(true);
        engine.setTrackingListener(() -> {
            if (mode == MODE_NAVIGATION) showBackToDriving();
        });
        // The bar starts from zero for each trip, in the chosen route's color.
        final MapEngine.Route route = routes.get(chosen);
        tripMeters = route.along.length > 0 ? route.along[route.along.length - 1] : 0;
        progressRoute = route;
        panelProgress.reset(MapKinds.routeColor(route.index), stopFractions(route));
        engine.startNavigation(route, this::onNavigation);
        if (voice == null) voice = new VoiceGuide(host.fragment().getParentActivity() != null ? host.fragment().getParentActivity() : org.telegram.messenger.ApplicationLoader.applicationContext);
        voice.onStart();
        updateMuteButton();
    }

    /**
     * The trip's progress on the bar: the way done against the whole trip as first planned, so a new route after a
     * wrong turn or a stop added carries on from where the car is instead of starting over.
     */
    private void updateTripProgress(MapEngine.NavigationState state) {
        if (state.route != null && state.route != progressRoute) {
            progressRoute = state.route;
            panelProgress.setStops(stopFractions(state.route));
        }
        final float total = Math.max(tripMeters, state.remainingMeters);
        panelProgress.setProgress(total > 0 ? 1 - state.remainingMeters / total : 0);
    }

    /** Where the route's stops are on the bar, as fractions of the whole trip. */
    private float[] stopFractions(MapEngine.Route route) {
        if (route.stops == null || route.stops.isEmpty() || route.points.isEmpty() || tripMeters <= 0) return new float[0];
        final float length = route.along[route.along.length - 1];
        final float[] fractions = new float[route.stops.size()];
        for (int s = 0; s < fractions.length; s++) {
            final MapEngine.Place stop = route.stops.get(s);
            double best = Double.MAX_VALUE;
            int index = 0;
            for (int i = 0; i < route.points.size(); i++) {
                final double d = MapEngine.distance(stop.latitude, stop.longitude, route.points.get(i)[0], route.points.get(i)[1]);
                if (d < best) {
                    best = d;
                    index = i;
                }
            }
            final float total = Math.max(tripMeters, length);
            fractions[s] = Math.max(0, Math.min(1, 1 - (length - route.along[index]) / total));
        }
        return fractions;
    }

    private void onNavigation(MapEngine.NavigationState state) {
        if (mode != MODE_NAVIGATION) return;
        if (state.arrived) {
            final String name = destination != null ? destination.name : "";
            // "You have arrived" is said to the end, so the voice is not stopped with the trip.
            if (voice != null) voice.onArrived();
            arriving = true;
            endTrip();
            arriving = false;
            BulletinFactory.of(host.fragment()).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.PhogramNavArrived, name)).show();
            return;
        }
        if (state.rerouting) {
            bannerTurn.setTurn(MapEngine.Maneuver.STRAIGHT, 0);
            bannerDistance.clear();
            bannerStreet.setText(getString(R.string.PhogramNavRerouting));
            bannerThen.setVisibility(View.GONE);
        } else if (state.next != null) {
            bannerTurn.setTurn(state.next);
            bannerDistance.set(MapsFormat.distanceParts(state.toNext), true);
            bannerStreet.setText(MapKinds.describe(state.next));
            // The turn after next, when it comes soon after this one.
            if (state.then != null && state.then.distance - state.next.distance < 300) {
                bannerThenTurn.setTurn(state.then);
                bannerThen.setVisibility(View.VISIBLE);
            } else {
                bannerThen.setVisibility(View.GONE);
            }
        }
        panelArrival.set(MapsFormat.arrivalParts(state.remainingSeconds), true);
        panelTime.set(MapsFormat.durationParts(state.remainingSeconds), true);
        panelDistance.set(MapsFormat.distanceParts(state.remainingMeters), true);
        updateTripProgress(state);
        updateDriving(state);
        if (voice != null) voice.onNavigation(state);
    }

    /**
     * Ends the trip altogether: the navigation stops, the routes leave the map and the page goes back to the map with
     * no place shown, as when the trip is over.
     */
    private void endTrip() {
        final MapEngine engine = host.engine();
        if (engine != null) engine.stopNavigation();
        setKeepScreenOn(false);
        close();
        host.onTripEnded();
    }

    private void setKeepScreenOn(boolean on) {
        final Activity activity = host.fragment().getParentActivity();
        if (activity == null) return;
        if (on) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Leaves the preview or the navigation and takes the routes off the map. */
    /** Set while the trip ends on arrival, so the arrival phrase is not cut off. */
    private boolean arriving;

    void close() {
        final MapEngine engine = host.engine();
        if (voice != null && !arriving) voice.release();
        if (engine != null) {
            engine.stopNavigation();
            engine.clearRoute();
        }
        setKeepScreenOn(false);
        mode = MODE_NONE;
        routes.clear();
        updateMode();
    }

    /**
     * Back from the navigation asks first, with Telegram's red End, so a stray press does not end the trip; from the
     * preview it goes back to the place's card.
     */
    boolean onBackPressed() {
        if (mode == MODE_NAVIGATION) {
            final Activity activity = host.fragment().getParentActivity();
            if (activity == null) return true;
            final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(getString(R.string.PhogramNavEndTitle));
            builder.setMessage(getString(R.string.PhogramNavEndText));
            builder.setPositiveButton(getString(R.string.PhogramNavEnd), (dialog, which) -> endTrip());
            builder.setNegativeButton(getString(R.string.Cancel), null);
            final AlertDialog dialog = builder.create();
            host.fragment().showDialog(dialog);
            final View end = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            if (end instanceof TextView) {
                ((TextView) end).setTextColor(Theme.getColor(Theme.key_text_RedBold));
            }
            return true;
        }
        if (mode == MODE_PREVIEW) {
            close();
            return true;
        }
        return false;
    }

    private void updateMode() {
        topCard.setVisibility(mode == MODE_PREVIEW ? View.VISIBLE : View.GONE);
        sheet.setVisibility(mode == MODE_PREVIEW ? View.VISIBLE : View.GONE);
        banner.setVisibility(mode == MODE_NAVIGATION ? View.VISIBLE : View.GONE);
        panel.setVisibility(mode == MODE_NAVIGATION ? View.VISIBLE : View.GONE);
        if (mode != MODE_NAVIGATION) bannerThen.setVisibility(View.GONE);
        final boolean driving = mode == MODE_NAVIGATION;
        speedGroup.setVisibility(driving ? View.VISIBLE : View.GONE);
        overviewButton.setVisibility(driving ? View.VISIBLE : View.GONE);
        muteButton.setVisibility(driving ? View.VISIBLE : View.GONE);
        if (!driving) {
            recenterButton.stop();
            recenterButton.setVisibility(View.GONE);
            warningChip.setVisibility(View.GONE);
            alongKind = MapEngine.KIND_OTHER;
            bindAlong(new java.util.ArrayList<>(), true);
            setExpanded(false);
        }
        layout();
        host.onModeChanged();
    }

    /** Places the cards under the status bar and above the tab bar. */
    void layout() {
        final int[] padding = host.padding();
        ((FrameLayout.LayoutParams) topCard.getLayoutParams()).topMargin = padding[1] + dp(8);
        ((FrameLayout.LayoutParams) banner.getLayoutParams()).topMargin = padding[1] + dp(8);
        ((FrameLayout.LayoutParams) bannerThen.getLayoutParams()).topMargin = padding[1] + dp(8) + Math.max(banner.getHeight(), dp(100)) - dp(4);
        sheet.setPadding(dp(16), dp(14), dp(16), dp(12) + padding[3]);
        panel.setPadding(0, 0, 0, padding[3]);
        // The driving extras sit above the panel, and the warning under the strip.
        final int above = (panel.getHeight() > 0 ? panel.getHeight() : dp(110)) + dp(12);
        ((FrameLayout.LayoutParams) speedGroup.getLayoutParams()).bottomMargin = above;
        ((FrameLayout.LayoutParams) overviewButton.getLayoutParams()).bottomMargin = above;
        ((FrameLayout.LayoutParams) muteButton.getLayoutParams()).bottomMargin = above + dp(56);
        ((FrameLayout.LayoutParams) recenterButton.getLayoutParams()).bottomMargin = above + dp(112);
        ((FrameLayout.LayoutParams) warningChip.getLayoutParams()).topMargin = padding[1] + dp(8) + Math.max(banner.getHeight(), dp(100)) + dp(8);
        speedGroup.requestLayout();
        topCard.requestLayout();
        banner.requestLayout();
    }

    void updateColors() {
        final int white = Theme.getColor(Theme.key_windowBackgroundWhite);
        final int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(dp(16));
        card.setColor(white);
        topCard.setBackground(card);
        topBack.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        topBack.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, dp(20)));
        fromText.setTextColor(gray);
        toText.setTextColor(black);
        dot(fromDot, Theme.getColor(Theme.key_windowBackgroundWhiteGreenText));
        dot(toDot, Theme.getColor(Theme.key_text_RedRegular));
        final GradientDrawable top = new GradientDrawable();
        top.setCornerRadii(new float[] { dp(16), dp(16), dp(16), dp(16), 0, 0, 0, 0 });
        top.setColor(white);
        sheet.setBackground(top);
        final GradientDrawable panelTop = new GradientDrawable();
        panelTop.setCornerRadii(new float[] { dp(16), dp(16), dp(16), dp(16), 0, 0, 0, 0 });
        panelTop.setColor(white);
        panel.setBackground(panelTop);
        timeText.setTextColor(black);
        statusText.setTextColor(black);
        viaText.setTextColor(gray);
        progress.setProgressColor(Theme.getColor(Theme.key_progressCircle));
        chipStyle(stepsButton, false);
        chipStyle(sendButton, false);
        startButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        startButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(10), accent, Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        // The strip takes the green of Telegram's bar for a call going on, with white on it.
        final GradientDrawable strip = new GradientDrawable();
        strip.setCornerRadius(dp(16));
        strip.setColor(Theme.getColor(Theme.key_returnToCallBackground));
        banner.setBackground(strip);
        final int onStrip = Theme.getColor(Theme.key_returnToCallText);
        bannerTurn.setColor(onStrip);
        bannerDistance.setTextColor(onStrip);
        bannerStreet.setTextColor(onStrip);
        final GradientDrawable thenBackground = new GradientDrawable();
        thenBackground.setCornerRadius(dp(15));
        thenBackground.setColor(Theme.getColor(Theme.key_returnToCallBackground));
        bannerThen.setBackground(thenBackground);
        bannerThenText.setTextColor(onStrip);
        bannerThenTurn.setColor(onStrip);
        panelArrival.setTextColor(black);
        panelTime.setTextColor(black);
        panelDistance.setTextColor(black);
        panelArrivalLabel.setTextColor(gray);
        panelTimeLabel.setTextColor(gray);
        panelDistanceLabel.setTextColor(gray);
        // Telegram's red for ending, as its End button for a call.
        final int red = Theme.getColor(Theme.key_text_RedRegular);
        endButton.setTextColor(red);
        endButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(10), (red & 0x00ffffff) | 0x1F000000, (red & 0x00ffffff) | 0x33000000));
        panelProgress.setColors(Theme.getColor(Theme.key_divider), Theme.getColor(Theme.key_windowBackgroundWhite));
        if (speedView != null) styleNavigationExtras();
        styleAlternatives();
    }

    private static void dot(View view, int color) {
        final GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        view.setBackground(circle);
    }

    private static void chipStyle(TextView chip, boolean on) {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(17));
        if (on) {
            background.setColor((accent & 0x00ffffff) | 0x26000000);
            background.setStroke(dp(1), accent);
            chip.setTextColor(accent);
        } else {
            background.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            chip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
        chip.setBackground(background);
    }
}
