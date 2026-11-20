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
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LineProgressView;
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
    private final TextView bannerDistance;
    private final TextView bannerStreet;
    private final LinearLayout bannerThen;
    private final TextView bannerThenText;
    private final MapKinds.TurnView bannerThenTurn;
    private final LinearLayout panel;
    private final TextView panelArrival;
    private final TextView panelTime;
    private final TextView panelDistance;
    private final TextView panelArrivalLabel;
    private final TextView panelTimeLabel;
    private final TextView panelDistanceLabel;
    private final TextView endButton;
    private final LineProgressView panelProgress;

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
        bannerDistance = text(context, 28, rtl);
        bannerDistance.setTypeface(AndroidUtilities.bold());
        banner.addView(bannerDistance, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, rtl ? 14 : 76, 8, rtl ? 76 : 14, 0));
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
        panelArrival = text(context, 22, rtl);
        panelArrivalLabel = text(context, 13, rtl);
        figures.addView(figure(context, panelArrival, panelArrivalLabel, getString(R.string.PhogramNavArrival)), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        panelTime = text(context, 22, rtl);
        panelTimeLabel = text(context, 13, rtl);
        figures.addView(figure(context, panelTime, panelTimeLabel, getString(R.string.PhogramNavTimeLeft)), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        panelDistance = text(context, 22, rtl);
        panelDistanceLabel = text(context, 13, rtl);
        figures.addView(figure(context, panelDistance, panelDistanceLabel, getString(R.string.PhogramNavDistanceLeft)), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        endButton = button(context, getString(R.string.PhogramNavEnd));
        endButton.setPadding(dp(18), 0, dp(18), 0);
        endButton.setOnClickListener(v -> endTrip());
        figures.addView(endButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44));
        panel.addView(figures, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 12));
        panelProgress = new LineProgressView(context);
        panel.addView(panelProgress, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 3, 16, 0, 16, 12));
        parent.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        // The page's round buttons stay above whichever panel is shown.
        final View.OnLayoutChangeListener resized = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) AndroidUtilities.runOnUIThread(host::onModeChanged);
        };
        sheet.addOnLayoutChangeListener(resized);
        panel.addOnLayoutChangeListener(resized);
        updateColors();
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

    private static LinearLayout figure(Context context, TextView value, TextView label, String name) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        value.setTypeface(AndroidUtilities.bold());
        value.setGravity(Gravity.CENTER);
        label.setGravity(Gravity.CENTER);
        label.setText(name);
        column.addView(value, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
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
            alternatives.addView(card, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, i < routes.size() - 1 ? 8 : 0, 0));
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
            turn.setTurn(maneuver.type, maneuver.exit);
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
        bannerDistance.setText("");
        bannerStreet.setText(getString(R.string.PhogramNavStarting));
        setKeepScreenOn(true);
        engine.startNavigation(routes.get(chosen), this::onNavigation);
    }

    private void onNavigation(MapEngine.NavigationState state) {
        if (mode != MODE_NAVIGATION) return;
        if (state.arrived) {
            final String name = destination != null ? destination.name : "";
            endTrip();
            BulletinFactory.of(host.fragment()).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.PhogramNavArrived, name)).show();
            return;
        }
        if (state.rerouting) {
            bannerTurn.setTurn(MapEngine.Maneuver.STRAIGHT, 0);
            bannerDistance.setText("");
            bannerStreet.setText(getString(R.string.PhogramNavRerouting));
            bannerThen.setVisibility(View.GONE);
        } else if (state.next != null) {
            bannerTurn.setTurn(state.next.type, state.next.exit);
            bannerDistance.setText(MapsFormat.distance(state.toNext));
            bannerStreet.setText(MapKinds.describe(state.next));
            // The turn after next, when it comes soon after this one.
            if (state.then != null && state.then.distance - state.next.distance < 300) {
                bannerThenTurn.setTurn(state.then.type, state.then.exit);
                bannerThen.setVisibility(View.VISIBLE);
            } else {
                bannerThen.setVisibility(View.GONE);
            }
        }
        panelArrival.setText(MapsFormat.arrival(state.remainingSeconds));
        panelTime.setText(MapsFormat.duration(state.remainingSeconds));
        panelDistance.setText(MapsFormat.distance(state.remainingMeters));
        panelProgress.setProgress(state.progress, true);
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
    void close() {
        final MapEngine engine = host.engine();
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
        panelProgress.setProgressColor(accent);
        panelProgress.setBackColor(Theme.getColor(Theme.key_divider));
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
