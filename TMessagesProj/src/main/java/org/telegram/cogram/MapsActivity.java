package org.telegram.cogram;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.cogram.gnss.GnssGauge;
import org.telegram.cogram.gnss.GnssMonitor;
import org.telegram.cogram.gnss.GnssSheet;
import org.telegram.cogram.measure.MapTools;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.utils.ViewOutlineProviderImpl;
import org.telegram.tgnet.TLRPC;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.LocationCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LineProgressView;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;

/**
 * Phogram's Maps tab, in the slot where Telegram has Settings (Settings opens from the chats page's options
 * menu, as Telegram does when Calls holds this slot).
 *
 * In builds with a map engine the page shows Telegram's progress circle until the tab is first opened, then
 * makes the map, so the engine costs nothing to users who never open the tab. The map fills the whole page,
 * under the status bar and the tab bar, with no header: the search is a floating "Where to?" bar at the bottom
 * that opens a sheet, and the map's buttons are Telegram's round location-page buttons. Builds without a map
 * engine show an empty state in Telegram's style.
 *
 * While a map is shown, swipes on it move the map instead of switching tabs.
 */
public class MapsActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private static final int LOCATION_PERMISSION_REQUEST = 7301;
    private static final String LOCATION_ASKED_KEY = "phogramMapsLocationAsked";
    private static final long COMPASS_CHECK_MS = 250;
    /** How often the strip's heading mark is brought up to date when the camera reports its own moves. */
    private static final long HEADING_CHECK_MS = 1000;

    private FrameLayout contentView;
    private FrameLayout mapContainer;
    private View topScrim;
    private ImageView backButton;
    private LinearLayout emptyView;
    private ImageView emptyIcon;
    private TextView emptyTitle;
    private TextView emptyText;
    private RadialProgressView progressView;
    private ImageView locationButton;
    private CompassStrip compassStrip;
    private FrameLayout downloadCard;
    private TextView downloadTitle;
    private ImageView downloadButton;
    private ImageView downloadClose;
    private LineProgressView downloadProgress;
    private String dismissedDownload;

    private static final long SEARCH_DELAY_MS = 300;
    /** A search that has not answered by then says it found nothing, so the sheet never waits for good. */
    private static final long SEARCH_TIMEOUT_MS = 15000;
    private static final int MAX_RECENT_PLACES = 10;
    private static final String RECENT_PREFERENCES = "cogram_maps_recent";
    private static final String RECENT_KEY = "places";
    private static final int SEARCH_BAR_HEIGHT = 48;
    /** The search bar's row, the place's card and the measure bar sit this close above the tab bar. */
    private static final int ROW_GAP = 6;

    private MapTools tools;
    private final GnssMonitor gnss = new GnssMonitor();
    private FrameLayout searchBar;
    private ImageView searchBarIcon;
    private TextView searchBarText;
    private View sheetScrim;
    private SearchSheet searchSheet;
    private View sheetHandle;
    private ImageView searchClose;
    private EditTextBoldCursor searchField;
    private ImageView searchClear;
    private RadialProgressView searchProgress;
    private LinearLayout chipsRow;
    private FrameLayout listFrame;
    private boolean searchOpen;
    private boolean searchRunning;
    private RecyclerListView searchList;
    private SearchAdapter searchAdapter;
    private TextView searchEmpty;
    private String searchQuery = "";
    private boolean searchDone;
    private final ArrayList<MapEngine.Place> searchResults = new ArrayList<>();
    /** How many of the results the list shows; it grows by a page as the user scrolls. */
    private int shownResults;
    private static final int RESULTS_PAGE = 15;
    private final ArrayList<MapEngine.Place> recentPlaces = new ArrayList<>();
    private final Runnable searchRunnable = this::runSearch;
    private final Runnable searchTimeout = () -> {
        if (searchDone || searchQuery.isEmpty()) return;
        searchResults.clear();
        searchDone = true;
        searchAdapter.notifyDataSetChanged();
        setSearchRunning(false);
        updateSearchEmpty();
    };

    private LinearLayout placeCard;
    private MapKinds.IconView placeIcon;
    private TextView placeName;
    private TextView placeDetails;
    private TextView placeAddress;
    private ImageView placeSend;
    private ImageView placeClose;
    private TextView placeRoute;
    private TextView routeInfo;
    private ImageView placeSave;
    private final ArrayList<MapEngine.Place> savedPlaces = new ArrayList<>();
    private static final String SAVED_KEY = "saved";
    private static final int MAX_SAVED_PLACES = 50;
    private ImageView placeShareOut;
    private ImageView placePin;
    private MapEngine.Place shownPlace;
    private View mapView;

    private MapEngine mapEngine;
    private boolean mapEngineRequested;
    private boolean mapReady;
    private boolean pageResumed;

    private boolean hasMainTabs;
    private int statusBarHeight;
    private int navigationBarHeight;

    /**
     * The map reports its camera moves, so the compass strip follows them without a timer; the timer then only
     * brings the heading mark up to date, as the GPS moves on.
     */
    private boolean cameraEvents;

    private final Runnable compassCheck = new Runnable() {
        @Override
        public void run() {
            updateCompass();
            if (pageResumed && mapEngine != null) {
                AndroidUtilities.runOnUIThread(this, cameraEvents ? HEADING_CHECK_MS : COMPASS_CHECK_MS);
            }
        }
    };

    private final Runnable createMapEngine = this::createMapEngine;

    private static final String ARG_LATITUDE = "placeLatitude";
    private static final String ARG_LONGITUDE = "placeLongitude";
    private static final String ARG_NAME = "placeName";
    private static final String ARG_ADDRESS = "placeAddress";
    private MapEngine.Place openedPlace;

    /**
     * The map screen a location in a chat opens, in place of Telegram's Google map: OsmAnd's map on the place,
     * with its card. Null when this build has no map engine, so the chat keeps Telegram's own screen.
     */
    public static MapsActivity forLocation(MessageObject message) {
        if (!MapEngines.isAvailable() || message == null || message.messageOwner == null || message.messageOwner.media == null
                || !(message.messageOwner.media.geo instanceof TLRPC.TL_geoPoint)) {
            return null;
        }
        final TLRPC.MessageMedia media = message.messageOwner.media;
        final Bundle args = new Bundle();
        args.putDouble(ARG_LATITUDE, media.geo.lat);
        args.putDouble(ARG_LONGITUDE, media.geo._long);
        final boolean venue = media instanceof TLRPC.TL_messageMediaVenue && !android.text.TextUtils.isEmpty(media.title);
        args.putString(ARG_NAME, venue ? media.title : getString(R.string.PhogramMapsSharedLocation));
        args.putString(ARG_ADDRESS, venue && !android.text.TextUtils.isEmpty(media.address) ? media.address
                : String.format(java.util.Locale.US, "%.5f, %.5f", media.geo.lat, media.geo._long));
        return new MapsActivity(args);
    }

    public MapsActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
            if (arguments.containsKey(ARG_LATITUDE)) {
                openedPlace = new MapEngine.Place(arguments.getString(ARG_NAME, ""), arguments.getString(ARG_ADDRESS, ""),
                        arguments.getDouble(ARG_LATITUDE), arguments.getDouble(ARG_LONGITUDE));
            }
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        statusBarHeight = AndroidUtilities.statusBarHeight;
        navigationBarHeight = AndroidUtilities.navigationBarHeight;

        contentView = new FrameLayout(context);

        // The page has no header: the map reaches the top of the screen, under the status bar.
        actionBar.setAddToContainer(false);
        loadRecentPlaces();

        // The map fills the whole page; the tab bar floats above it.
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

        // A soft fade under the status bar, so the clock, the network and the battery stay readable over the map.
        topScrim = new View(context);
        topScrim.setVisibility(View.GONE);
        contentView.addView(topScrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // Opened from a chat (not as a tab), the page leaves by a round back button over the map.
        backButton = new ImageView(context);
        ScaleStateListAnimator.apply(backButton);
        backButton.setTranslationZ(dp(2));
        backButton.setOutlineProvider(ViewOutlineProviderImpl.BOUNDS_OVAL);
        backButton.setImageResource(R.drawable.ic_ab_back);
        backButton.setScaleType(ImageView.ScaleType.CENTER);
        backButton.setContentDescription(getString(R.string.AccDescrGoBack));
        backButton.setVisibility(hasMainTabs ? View.GONE : View.VISIBLE);
        backButton.setOnClickListener(v -> finishFragment());
        contentView.addView(backButton, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.TOP, 12, 0, 0, 0));

        // The location page's round button on the right, above the search bar: the device's position.
        locationButton = new ImageView(context);
        ScaleStateListAnimator.apply(locationButton);
        locationButton.setTranslationZ(dp(2));
        locationButton.setOutlineProvider(ViewOutlineProviderImpl.BOUNDS_OVAL);
        locationButton.setImageResource(R.drawable.msg_current_location);
        locationButton.setScaleType(ImageView.ScaleType.CENTER);
        locationButton.setContentDescription(getString(R.string.AccDescrMyLocation));
        locationButton.setVisibility(View.GONE);
        locationButton.setOnClickListener(v -> onLocationButtonClick());
        // The position's quality as a gauge around the button; pressing and holding it opens the satellites, with a tap
        // felt as Telegram's long presses are.
        locationButton.setForeground(new GnssGauge(gnss));
        gnss.addListener(locationButton::invalidate);
        locationButton.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            GnssSheet.show(getParentActivity(), gnss);
            return true;
        });
        contentView.addView(locationButton, LayoutHelper.createFrame(48, 48, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.BOTTOM, 12, 0, 12, 12));

        // The map's heading as a strip of degrees under the status bar: dragged it turns the map, tapped it puts
        // north up. During a trip the strip in the turn's card takes its place.
        compassStrip = new CompassStrip(context);
        compassStrip.setContentDescription(getString(R.string.PhogramMapsNorthUp));
        compassStrip.setVisibility(View.GONE);
        compassStrip.setListener(new CompassStrip.Listener() {
            @Override
            public void onRotate(float bearing) {
                if (mapEngine != null) mapEngine.rotateTo(bearing);
            }

            @Override
            public void onTap() {
                if (mapEngine != null) mapEngine.resetRotation();
            }
        });
        contentView.addView(compassStrip, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 28, Gravity.TOP, 56, 0, 56, 0));

        // The offer of a map file to download, a small floating card at the top in the colors of Telegram's player
        // strip: the action on the left, the name in one line, the close button on the right, and a line that
        // fills with the download.
        downloadCard = new FrameLayout(context);
        downloadCard.setVisibility(View.GONE);
        downloadCard.setTranslationZ(dp(2));
        downloadCard.setClipToOutline(true);
        downloadCard.setOnClickListener(v -> onDownloadAction());
        downloadButton = new ImageView(context);
        downloadButton.setScaleType(ImageView.ScaleType.CENTER);
        downloadButton.setOnClickListener(v -> onDownloadAction());
        downloadCard.addView(downloadButton, LayoutHelper.createFrame(36, 36, Gravity.TOP | Gravity.LEFT, 8, 0, 0, 0));
        downloadTitle = new TextView(context);
        downloadTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        downloadTitle.setSingleLine(true);
        downloadTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        downloadTitle.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        downloadCard.addView(downloadTitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP | Gravity.LEFT, 35 + 16, 0, 36, 0));
        downloadClose = new ImageView(context);
        downloadClose.setImageResource(R.drawable.miniplayer_close);
        downloadClose.setScaleType(ImageView.ScaleType.CENTER);
        downloadClose.setContentDescription(getString(R.string.Close));
        downloadClose.setOnClickListener(v -> {
            // Closing stops a running download and hides this file's offer until the page is made again.
            if (mapEngine == null) return;
            final MapEngine.Download offer = mapEngine.getOfferedDownload();
            if (offer == null) return;
            if (offer.downloading) {
                mapEngine.cancelOfferedDownload();
            }
            dismissedDownload = offer.name;
            updateDownloadCard();
        });
        downloadCard.addView(downloadClose, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.TOP, 0, 0, 2, 0));
        downloadProgress = new LineProgressView(context);
        downloadCard.addView(downloadProgress, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.BOTTOM));
        contentView.addView(downloadCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP, 8, 0, 8, 0));

        // The pin of the place picked from the search: Telegram's map pin, its point on the place.
        placePin = new ImageView(context);
        placePin.setImageResource(R.drawable.map_pin2);
        placePin.setVisibility(View.GONE);
        contentView.addView(placePin, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));

        // The place card, above the tab bar: the place's emoji on its colored circle, its name, "type · distance" and
        // its address; small icons to share it, send it into a chat and close the card; and Go, wide and filled, with
        // the time there by car, beside the star that saves the place.
        placeCard = new LinearLayout(context);
        placeCard.setOrientation(LinearLayout.VERTICAL);
        placeCard.setTranslationZ(dp(2));
        placeCard.setVisibility(View.GONE);
        placeCard.setClickable(true);
        final boolean rtl = LocaleController.isRTL;
        final FrameLayout placeHeader = new FrameLayout(context);
        placeIcon = new MapKinds.IconView(context);
        placeHeader.addView(placeIcon, LayoutHelper.createFrame(44, 44, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, rtl ? 0 : 14, 14, rtl ? 14 : 0, 0));
        final LinearLayout placeTexts = new LinearLayout(context);
        placeTexts.setOrientation(LinearLayout.VERTICAL);
        placeName = new TextView(context);
        placeName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        placeName.setTypeface(AndroidUtilities.bold());
        placeName.setMaxLines(2);
        placeName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        placeName.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        placeTexts.addView(placeName, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        placeDetails = new TextView(context);
        placeDetails.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        placeDetails.setSingleLine(true);
        placeDetails.setEllipsize(android.text.TextUtils.TruncateAt.END);
        placeDetails.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        placeTexts.addView(placeDetails, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        placeAddress = new TextView(context);
        placeAddress.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        placeAddress.setSingleLine(true);
        placeAddress.setEllipsize(android.text.TextUtils.TruncateAt.END);
        placeAddress.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        placeTexts.addView(placeAddress, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        placeHeader.addView(placeTexts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP,
                rtl ? 116 : 68, 14, rtl ? 68 : 116, 4));
        // The small icons, all the same size: share outside the app, send into a chat, close.
        final LinearLayout placeIcons = new LinearLayout(context);
        placeIcons.setOrientation(LinearLayout.HORIZONTAL);
        placeShareOut = new ImageView(context);
        placeShareOut.setImageResource(R.drawable.msg_share);
        placeShareOut.setScaleType(ImageView.ScaleType.CENTER);
        placeShareOut.setContentDescription(getString(R.string.PhogramMapsSharePlace));
        placeShareOut.setOnClickListener(v -> sharePlace());
        placeSend = new ImageView(context);
        placeSend.setImageResource(R.drawable.msg_send);
        placeSend.setScaleType(ImageView.ScaleType.CENTER);
        placeSend.setContentDescription(getString(R.string.PhogramMapsSendToChat));
        placeSend.setOnClickListener(v -> sendPlaceToChat());
        placeClose = new ImageView(context);
        placeClose.setImageResource(R.drawable.miniplayer_close);
        placeClose.setScaleType(ImageView.ScaleType.CENTER);
        placeClose.setContentDescription(getString(R.string.Close));
        placeClose.setOnClickListener(v -> hidePlace());
        // The close button stays at the card's edge, on whichever side the language reads from.
        final ImageView[] order = rtl ? new ImageView[] { placeClose, placeShareOut, placeSend } : new ImageView[] { placeSend, placeShareOut, placeClose };
        for (ImageView icon : order) {
            placeIcons.addView(icon, LayoutHelper.createLinear(34, 34, 0, 0, 3, 0));
        }
        placeHeader.addView(placeIcons, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 34, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, rtl ? 8 : 0, 10, rtl ? 0 : 8, 0));
        placeCard.addView(placeHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        // The way there by car, when it could not be found: why.
        routeInfo = new TextView(context);
        routeInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        routeInfo.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        routeInfo.setVisibility(View.GONE);
        placeCard.addView(routeInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 18, 0, 18, 6));
        // Go, filled and wide, with the time by car; the star beside it, the same height.
        final LinearLayout placeButtons = new LinearLayout(context);
        placeButtons.setOrientation(LinearLayout.HORIZONTAL);
        placeRoute = new TextView(context);
        placeRoute.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        placeRoute.setTypeface(AndroidUtilities.bold());
        placeRoute.setGravity(Gravity.CENTER);
        placeRoute.setText(getString(R.string.PhogramNavGo));
        placeRoute.setCompoundDrawablePadding(dp(8));
        ScaleStateListAnimator.apply(placeRoute, 0.02f, 1.2f);
        placeRoute.setOnClickListener(v -> showRouteToPlace());
        placeSave = new ImageView(context);
        placeSave.setScaleType(ImageView.ScaleType.CENTER);
        placeSave.setOnClickListener(v -> showPlaceOptions());
        ScaleStateListAnimator.apply(placeSave, 0.04f, 1.2f);
        if (rtl) {
            placeButtons.addView(placeSave, LayoutHelper.createLinear(48, 48, 0, 0, 8, 0));
            placeButtons.addView(placeRoute, LayoutHelper.createLinear(0, 48, 1f));
        } else {
            placeButtons.addView(placeRoute, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 8, 0));
            placeButtons.addView(placeSave, LayoutHelper.createLinear(48, 48));
        }
        placeCard.addView(placeButtons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 14, 8, 14, 14));
        contentView.addView(placeCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 8, 0, 8, 8));
        // The round buttons stay above the card whatever its height.
        placeCard.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) {
                AndroidUtilities.runOnUIThread(this::updateLayout);
            }
        });

        createSearch(context);
        // A page built again (as when the theme turns to night): the old panel's trip ends with it.
        if (routeUi != null) routeUi.release();
        routeUi = new MapsRouteUi(context, contentView, new MapsRouteUi.Host() {
            @Override
            public MapEngine engine() {
                return mapEngine;
            }

            @Override
            public BaseFragment fragment() {
                return MapsActivity.this;
            }

            @Override
            public int[] padding() {
                return getMapPadding();
            }

            @Override
            public void onModeChanged() {
                onRouteModeChanged();
            }

            @Override
            public void sendToChat(MapEngine.Place place) {
                sendPlaceToChat();
            }

            @Override
            public void askLocation() {
                onLocationButtonClick();
            }

            @Override
            public boolean hasLocationPermission() {
                final Activity activity = getParentActivity();
                return activity != null && (Build.VERSION.SDK_INT < 23 || MapsActivity.hasLocationPermission(activity));
            }

            @Override
            public void onTripEnded() {
                hidePlace();
            }

            @Override
            public void shareTrip(String text) {
                shareTripText(text);
            }
        });

        final boolean engineAvailable = MapEngines.isAvailable();
        emptyView.setVisibility(engineAvailable ? View.GONE : View.VISIBLE);
        progressView.setVisibility(engineAvailable ? View.VISIBLE : View.GONE);
        // Telegram rebuilds hidden pages, as when the theme turns to night: the running engine moves to the new page.
        if (mapEngine != null) {
            mapView = null;
            // The trip's panel was on the old page; the trip ends rather than going on without it.
            mapEngine.stopNavigation();
            attachMapEngine();
        }

        updateColors();
        updateLayout();
        ViewCompat.setOnApplyWindowInsetsListener(contentView, this::onApplyWindowInsets);
        return fragmentView = contentView;
    }

    /**
     * The search, as Waze has it: a floating "Where to?" bar above the tab bar opens a sheet from the bottom with
     * the field, the categories, and the recent places until something is typed. Back, the close button, a tap on
     * the map above the sheet or dragging the sheet down all close it.
     */
    private void createSearch(Context context) {
        searchBar = new FrameLayout(context);
        searchBar.setVisibility(View.GONE);
        searchBar.setTranslationZ(dp(3));
        searchBar.setContentDescription(getString(R.string.PhogramMapsWhereTo));
        ScaleStateListAnimator.apply(searchBar, 0.02f, 1.2f);
        searchBar.setOnClickListener(v -> openSearch());
        searchBarIcon = new ImageView(context);
        searchBarIcon.setImageResource(R.drawable.outline_header_search);
        searchBarIcon.setScaleType(ImageView.ScaleType.CENTER);
        searchBar.addView(searchBarIcon, LayoutHelper.createFrame(48, 48, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 4, 0, 4, 0));
        searchBarText = new TextView(context);
        searchBarText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        searchBarText.setSingleLine(true);
        searchBarText.setText(getString(R.string.PhogramMapsWhereTo));
        searchBarText.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        searchBar.addView(searchBarText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP,
                LocaleController.isRTL ? 16 : 52, 0, LocaleController.isRTL ? 52 : 16, 0));
        contentView.addView(searchBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, SEARCH_BAR_HEIGHT, Gravity.BOTTOM,
                LocaleController.isRTL ? MapTools.END_MARGIN : MapTools.START_MARGIN, 0, LocaleController.isRTL ? MapTools.START_MARGIN : MapTools.END_MARGIN, 0));
        // As in Telegram's chat header: "my location" round at the line's start, the layers and the tools at its end.
        tools = new MapTools(this, contentView, () -> {
            updateSearchBar();
            updateLayout();
        });

        sheetScrim = new View(context);
        sheetScrim.setVisibility(View.GONE);
        sheetScrim.setOnClickListener(v -> closeSearch());
        contentView.addView(sheetScrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        searchSheet = new SearchSheet(context);
        searchSheet.setOrientation(LinearLayout.VERTICAL);
        searchSheet.setVisibility(View.GONE);
        searchSheet.setClickable(true);
        searchSheet.setTranslationZ(dp(4));

        // Telegram's sheet handle: the sheet is dragged down by it, or anywhere above the list.
        final FrameLayout handleFrame = new FrameLayout(context);
        sheetHandle = new View(context);
        handleFrame.addView(sheetHandle, LayoutHelper.createFrame(36, 4, Gravity.CENTER));
        searchSheet.addView(handleFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 16));

        final FrameLayout header = new FrameLayout(context);
        searchClose = new ImageView(context);
        searchClose.setImageResource(R.drawable.ic_close_white);
        searchClose.setScaleType(ImageView.ScaleType.CENTER);
        searchClose.setContentDescription(getString(R.string.Close));
        searchClose.setOnClickListener(v -> closeSearch());
        header.addView(searchClose, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        searchField = new EditTextBoldCursor(context);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setPadding(0, 0, 0, 0);
        searchField.setHint(getString(R.string.PhogramMapsSearchHint));
        searchField.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchField.setCursorSize(dp(20));
        searchField.setCursorWidth(1.5f);
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                AndroidUtilities.hideKeyboard(searchField);
                return true;
            }
            return false;
        });
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onSearchTextChanged(s.toString().trim());
            }
        });
        header.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP, 56, 0, 48, 0));
        searchClear = new ImageView(context);
        searchClear.setImageResource(R.drawable.msg_clear_input);
        searchClear.setScaleType(ImageView.ScaleType.CENTER);
        searchClear.setContentDescription(getString(R.string.ClearButton));
        searchClear.setVisibility(View.GONE);
        searchClear.setOnClickListener(v -> {
            searchField.setText("");
            AndroidUtilities.showKeyboard(searchField);
        });
        header.addView(searchClear, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        searchProgress = new RadialProgressView(context, getResourceProvider());
        searchProgress.setSize(dp(18));
        searchProgress.setVisibility(View.GONE);
        header.addView(searchProgress, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        searchSheet.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        // Places to look for with one tap: tiles with the kind's Telegram emoji on its colored circle, as on the
        // search results; each one searches its word in OsmAnd's place types.
        final HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setClipToPadding(false);
        chipsScroll.setPadding(dp(10), 0, dp(10), 0);
        chipsRow = new LinearLayout(context);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        final int[][] categories = {
                { R.string.PhogramMapsRestaurants, R.string.PhogramMapsRestaurantsQuery, MapEngine.KIND_FOOD },
                { R.string.PhogramMapsFuel, R.string.PhogramMapsFuelQuery, MapEngine.KIND_FUEL },
                { R.string.PhogramMapsHospitals, R.string.PhogramMapsHospitalsQuery, MapEngine.KIND_HEALTH },
                { R.string.PhogramMapsPharmacies, R.string.PhogramMapsPharmaciesQuery, MapEngine.KIND_PHARMACY },
                { R.string.PhogramMapsMosques, R.string.PhogramMapsMosquesQuery, MapEngine.KIND_WORSHIP },
                { R.string.PhogramMapsAtms, R.string.PhogramMapsAtmsQuery, MapEngine.KIND_MONEY },
        };
        for (int[] category : categories) {
            final LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(0, dp(8), 0, dp(6));
            final MapKinds.IconView icon = new MapKinds.IconView(context);
            icon.setKind(category[2]);
            tile.addView(icon, LayoutHelper.createLinear(40, 40, Gravity.CENTER_HORIZONTAL));
            final TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setText(getString(category[0]));
            tile.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 4, 4, 0));
            final String query = getString(category[1]);
            ScaleStateListAnimator.apply(tile, 0.04f, 1.2f);
            tile.setOnClickListener(v -> {
                searchField.setText(query);
                searchField.setSelection(searchField.length());
                AndroidUtilities.hideKeyboard(searchField);
            });
            chipsRow.addView(tile, LayoutHelper.createLinear(76, 80, 0f, Gravity.CENTER_VERTICAL, 3, 0, 3, 0));
        }
        chipsScroll.addView(chipsRow, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, 88, Gravity.LEFT));
        // Right to left in Arabic, Kurdish and Persian: the first category on the right, the row scrolled to it.
        if (LocaleController.isRTL) {
            chipsScroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            chipsRow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            chipsScroll.post(() -> chipsScroll.fullScroll(View.FOCUS_RIGHT));
        }
        searchSheet.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 88, 0, 0, 0, 4));

        listFrame = new FrameLayout(context);
        searchList = new RecyclerListView(context, getResourceProvider());
        searchList.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        searchList.setAdapter(searchAdapter = new SearchAdapter(context));
        searchList.setClipToPadding(false);
        searchList.setContentDescription(getString(R.string.PhogramMapsSearchResults));
        searchList.setOnItemClickListener((view, position) -> {
            final MapEngine.Place place = searchAdapter.placeAt(position);
            if (place == null) return;
            addRecentPlace(place);
            closeSearch();
            showPlace(place);
        });
        searchList.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    AndroidUtilities.hideKeyboard(searchField);
                }
            }

            // The results come 15 at a time: near the end of the list, the next 15 are added.
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (searchQuery.isEmpty() || shownResults >= searchResults.size()) return;
                final LinearLayoutManager layout = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (layout != null && layout.findLastVisibleItemPosition() >= shownResults - 3) {
                    final int from = shownResults;
                    shownResults = Math.min(searchResults.size(), shownResults + RESULTS_PAGE);
                    final int to = shownResults;
                    recyclerView.post(() -> {
                        searchAdapter.notifyItemChanged(from - 1);
                        searchAdapter.notifyItemRangeInserted(from, to - from);
                    });
                }
            }
        });
        listFrame.addView(searchList, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        searchEmpty = new TextView(context);
        searchEmpty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchEmpty.setGravity(Gravity.CENTER_HORIZONTAL);
        searchEmpty.setPadding(dp(32), dp(48), dp(32), 0);
        searchEmpty.setText(getString(R.string.NoResult));
        searchEmpty.setVisibility(View.GONE);
        listFrame.addView(searchEmpty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        searchSheet.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        contentView.addView(searchSheet, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));
    }

    private void openSearch() {
        if (searchOpen || mapEngine == null) return;
        searchOpen = true;
        searchAdapter.notifyDataSetChanged();
        updateSearchEmpty();
        sheetScrim.setVisibility(View.VISIBLE);
        sheetScrim.animate().cancel();
        sheetScrim.animate().alpha(1f).setDuration(250).start();
        searchSheet.setVisibility(View.VISIBLE);
        searchSheet.animate().cancel();
        searchSheet.setTranslationY(contentView.getHeight());
        searchSheet.animate().translationY(0).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        searchField.requestFocus();
        AndroidUtilities.showKeyboard(searchField);
    }

    /** Closes the sheet and forgets what was typed; the recent places stay for the next time. */
    private void closeSearch() {
        if (!searchOpen) return;
        searchOpen = false;
        AndroidUtilities.hideKeyboard(searchField);
        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        AndroidUtilities.cancelRunOnUIThread(searchTimeout);
        sheetScrim.animate().cancel();
        sheetScrim.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (!searchOpen) sheetScrim.setVisibility(View.GONE);
        }).start();
        searchSheet.animate().cancel();
        searchSheet.animate().translationY(Math.max(searchSheet.getHeight(), dp(100))).setDuration(220)
                .setInterpolator(CubicBezierInterpolator.EASE_IN).withEndAction(() -> {
                    if (searchOpen) return;
                    searchSheet.setVisibility(View.GONE);
                    searchField.setText("");
                }).start();
    }

    private void onSearchTextChanged(String query) {
        if (query.equals(searchQuery)) return;
        searchQuery = query;
        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        AndroidUtilities.cancelRunOnUIThread(searchTimeout);
        searchResults.clear();
        searchDone = false;
        searchAdapter.notifyDataSetChanged();
        if (query.isEmpty()) {
            setSearchRunning(false);
        } else {
            setSearchRunning(true);
            AndroidUtilities.runOnUIThread(searchRunnable, SEARCH_DELAY_MS);
        }
        updateSearchEmpty();
    }

    private void setSearchRunning(boolean running) {
        searchRunning = running;
        searchProgress.setVisibility(running ? View.VISIBLE : View.GONE);
        searchClear.setVisibility(!running && !searchQuery.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // The recent places, kept on the phone only, newest first.

    private static SharedPreferences recentPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(RECENT_PREFERENCES, Context.MODE_PRIVATE);
    }

    private void loadRecentPlaces() {
        loadPlaces(RECENT_KEY, recentPlaces, MAX_RECENT_PLACES);
        loadPlaces(SAVED_KEY, savedPlaces, MAX_SAVED_PLACES);
    }

    private static void loadPlaces(String key, ArrayList<MapEngine.Place> places, int max) {
        places.clear();
        try {
            final JSONArray array = new JSONArray(recentPreferences().getString(key, "[]"));
            for (int i = 0; i < array.length() && places.size() < max; i++) {
                final JSONObject item = array.getJSONObject(i);
                places.add(new MapEngine.Place(item.optString("name"), item.optString("address"),
                        item.getDouble("lat"), item.getDouble("lon")));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static boolean samePlace(MapEngine.Place a, MapEngine.Place b) {
        return a.name.equals(b.name) && Math.abs(a.latitude - b.latitude) < 1e-6 && Math.abs(a.longitude - b.longitude) < 1e-6;
    }

    private static int indexOfPlace(ArrayList<MapEngine.Place> places, MapEngine.Place place) {
        for (int i = 0; i < places.size(); i++) {
            if (samePlace(places.get(i), place)) return i;
        }
        return -1;
    }

    private void addRecentPlace(MapEngine.Place place) {
        int index;
        while ((index = indexOfPlace(recentPlaces, place)) >= 0) {
            recentPlaces.remove(index);
        }
        recentPlaces.add(0, place);
        while (recentPlaces.size() > MAX_RECENT_PLACES) {
            recentPlaces.remove(recentPlaces.size() - 1);
        }
        savePlaces(RECENT_KEY, recentPlaces);
    }

    private void clearRecentPlaces() {
        recentPlaces.clear();
        savePlaces(RECENT_KEY, recentPlaces);
        searchAdapter.notifyDataSetChanged();
    }

    /** Saves the shown place, or takes it off the saved places when it is there. */
    /** Telegram's menu for the shown place: save it (or take it off), or make it home or work. */
    private void showPlaceOptions() {
        final MapEngine.Place place = shownPlace;
        if (place == null || getParentActivity() == null) return;
        final boolean saved = indexOfPlace(savedPlaces, place) >= 0;
        final CharSequence[] items = {
                getString(saved ? R.string.PhogramMapsUnsave : R.string.PhogramMapsSave),
                getString(R.string.PhogramMapsSetHome),
                getString(R.string.PhogramMapsSetWork),
        };
        final int[] icons = { saved ? R.drawable.msg_unfave : R.drawable.msg_fave, R.drawable.msg_home, R.drawable.msg_work };
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(place.name);
        builder.setItems(items, icons, (dialog, which) -> {
            if (shownPlace != place) return;
            if (which == 0) {
                toggleSavedPlace();
            } else {
                setMyPlace(which == 1 ? 0 : 1, place);
            }
        });
        showDialog(builder.create());
    }

    private void toggleSavedPlace() {
        final MapEngine.Place place = shownPlace;
        if (place == null) return;
        final int index = indexOfPlace(savedPlaces, place);
        if (index >= 0) {
            savedPlaces.remove(index);
        } else {
            savedPlaces.add(0, place);
            while (savedPlaces.size() > MAX_SAVED_PLACES) {
                savedPlaces.remove(savedPlaces.size() - 1);
            }
        }
        savePlaces(SAVED_KEY, savedPlaces);
        updatePlaceSave();
        searchAdapter.notifyDataSetChanged();
    }

    private void updatePlaceSave() {
        if (placeSave == null) return;
        final boolean saved = shownPlace != null && indexOfPlace(savedPlaces, shownPlace) >= 0;
        placeSave.setImageResource(saved ? R.drawable.msg_unfave : R.drawable.msg_fave);
        placeSave.setContentDescription(getString(saved ? R.string.PhogramMapsUnsave : R.string.PhogramMapsSave));
    }

    private static void savePlaces(String key, ArrayList<MapEngine.Place> places) {
        try {
            final JSONArray array = new JSONArray();
            for (MapEngine.Place place : places) {
                final JSONObject item = new JSONObject();
                item.put("name", place.name);
                item.put("address", place.address == null ? "" : place.address);
                item.put("lat", place.latitude);
                item.put("lon", place.longitude);
                array.put(item);
            }
            recentPreferences().edit().putString(key, array.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // Directions: the preview of the ways to the shown place, then the navigation (MapsRouteUi).

    private MapsRouteUi routeUi;

    private void showRouteToPlace() {
        final MapEngine.Place place = shownPlace;
        if (place == null || mapEngine == null || routeUi == null) return;
        routeUi.preview(place);
    }

    private boolean isRouting() {
        return routeUi != null && routeUi.getMode() != MapsRouteUi.MODE_NONE;
    }

    /** The preview or the navigation opened or closed: the page's own cards and buttons make room for them. */
    private void onRouteModeChanged() {
        if (contentView == null) return;
        final boolean routing = isRouting();
        final boolean navigating = routeUi != null && routeUi.getMode() == MapsRouteUi.MODE_NAVIGATION;
        placeCard.setVisibility(!routing && shownPlace != null ? View.VISIBLE : View.GONE);
        if (mapView != null && mapReady) {
            locationButton.setVisibility(navigating ? View.GONE : View.VISIBLE);
        }
        topScrim.setVisibility(mapView != null && !routing ? View.VISIBLE : View.GONE);
        updateSearchBar();
        updateDownloadCard();
        updateLayout();
        updateCompass();
    }

    /** The search sheet, dragged down by its top or by the list once it is scrolled to its start. */
    private class SearchSheet extends LinearLayout {

        private final int touchSlop;
        private float startX;
        private float startY;
        private boolean dragging;
        private VelocityTracker velocityTracker;

        SearchSheet(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = ev.getRawX();
                    startY = ev.getRawY();
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    final float dx = ev.getRawX() - startX;
                    final float dy = ev.getRawY() - startY;
                    if (!dragging && dy > touchSlop && dy > Math.abs(dx)
                            && (ev.getY() < listFrame.getTop() || !searchList.canScrollVertically(-1))) {
                        startDrag(ev);
                        return true;
                    }
                    break;
            }
            return dragging;
        }

        private void startDrag(MotionEvent ev) {
            dragging = true;
            startY = ev.getRawY() - getTranslationY();
            animate().cancel();
            AndroidUtilities.hideKeyboard(searchField);
            if (velocityTracker != null) velocityTracker.recycle();
            velocityTracker = VelocityTracker.obtain();
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            final int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                // A touch on the handle, or on the sheet around its views, drags the sheet at once.
                startDrag(ev);
            }
            if (!dragging) return super.onTouchEvent(ev);
            if (velocityTracker != null) {
                // The sheet itself moves, so the speed is taken in screen coordinates.
                final MotionEvent screen = MotionEvent.obtain(ev);
                screen.setLocation(ev.getRawX(), ev.getRawY());
                velocityTracker.addMovement(screen);
                screen.recycle();
            }
            if (action == MotionEvent.ACTION_MOVE) {
                setTranslationY(Math.max(0, ev.getRawY() - startY));
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float velocity = 0;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    velocity = velocityTracker.getYVelocity();
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                dragging = false;
                if (getTranslationY() > getHeight() / 4f || velocity > dp(1000)) {
                    closeSearch();
                } else {
                    animate().translationY(0).setDuration(200).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                }
            }
            return true;
        }
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        if (pendingSentNotice != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingSentNotice);
            pendingSentNotice.run();
        }
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
        attachMapEngine();
    }

    /** Puts the engine's map on this page and wires it; again on a page rebuilt with the same engine. */
    private void attachMapEngine() {
        // The progress circle shows over the map until its style is in; the map's own cover hides it until it draws.
        // (A TextureView gets its surface only when drawn, so the map is never hidden: it would then never start.)
        mapReady = false;
        setMapView(mapEngine.getView());
        applyMapPadding();
        tools.setEngine(mapEngine);
        cameraEvents = mapEngine.setCameraMoveListener(() -> {
            updateCompass();
            tools.onCameraMoved();
        });
        if (pageResumed) {
            mapEngine.onResume();
            AndroidUtilities.runOnUIThread(compassCheck, COMPASS_CHECK_MS);
        }
        mapEngine.setDownloadListener(this::updateDownloadCard);
        mapEngine.setOnReadyListener(this::onMapReady);
        // A tap on a named place opens its card; pressing and holding anywhere drops a pin there with its card.
        mapEngine.setMapTapListener(new MapEngine.MapTapListener() {
            @Override
            public void onPlaceTapped(MapEngine.Place place) {
                if (!searchOpen && !isRouting() && !tools.isMeasuring()) showPlace(place);
            }

            @Override
            public void onLongPressed(double latitude, double longitude) {
                if (searchOpen || isRouting() || tools.isMeasuring()) return;
                showPlace(new MapEngine.Place(getString(R.string.PhogramMapsDroppedPin),
                        String.format(java.util.Locale.US, "%.5f, %.5f", latitude, longitude), latitude, longitude));
            }

            @Override
            public void onRouteTapped(int index) {
                if (routeUi != null) routeUi.selectRoute(index);
            }

            @Override
            public void onAlongTapped(MapEngine.Place place) {
                if (routeUi != null) routeUi.offerStop(place);
            }

            @Override
            public void onDrawingTapped(String id) {
                if (!searchOpen && !isRouting() && !tools.isMeasuring()) tools.openDrawing(id);
            }

            // A tap on the bare map closes the place's card, as in Google Maps.
            @Override
            public void onEmptyTapped() {
                if (shownPlace != null && !searchOpen && !isRouting()) hidePlace();
            }
        });
    }

    private void updateDownloadCard() {
        if (downloadCard == null) return;
        final MapEngine.Download offer = mapEngine != null && mapReady ? mapEngine.getOfferedDownload() : null;
        final boolean show = offer != null && !isRouting() && (offer.downloading || !offer.name.equals(dismissedDownload));
        if (show) {
            // As the player's "performer - title": the name stands out, the state follows in the title color.
            final String state = offer.downloading
                    ? LocaleController.formatString(R.string.PhogramMapsDownloading, (int) offer.progress)
                    : LocaleController.formatString(R.string.PhogramMapsOffline, offer.size);
            final android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(offer.name);
            text.setSpan(new org.telegram.ui.Components.TypefaceSpan(AndroidUtilities.bold(), 0, getThemedColor(Theme.key_inappPlayerPerformer)), 0, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append("  ").append(state);
            downloadTitle.setText(text);
            downloadButton.setImageResource(offer.downloading ? R.drawable.msg_cancel : R.drawable.msg_download);
            downloadButton.setContentDescription(getString(offer.downloading ? R.string.Cancel : R.string.PhogramMapsDownload));
            downloadProgress.setProgress(offer.downloading ? offer.progress / 100f : 0, offer.downloading);
        }
        if (show != (downloadCard.getVisibility() == View.VISIBLE)) {
            downloadCard.setVisibility(show ? View.VISIBLE : View.GONE);
            updateLayout();
        }
    }

    private void runSearch() {
        if (mapEngine == null || searchQuery.isEmpty()) return;
        final String query = searchQuery;
        searchDone = false;
        AndroidUtilities.cancelRunOnUIThread(searchTimeout);
        AndroidUtilities.runOnUIThread(searchTimeout, SEARCH_TIMEOUT_MS);
        mapEngine.search(query, (resultQuery, places) -> {
            if (!resultQuery.equals(searchQuery)) return;
            // A late answer after the time limit still replaces "No results".
            AndroidUtilities.cancelRunOnUIThread(searchTimeout);
            searchResults.clear();
            searchResults.addAll(places);
            shownResults = Math.min(RESULTS_PAGE, searchResults.size());
            searchDone = true;
            searchAdapter.notifyDataSetChanged();
            setSearchRunning(false);
            updateSearchEmpty();
        });
    }

    private void updateSearchEmpty() {
        if (searchEmpty == null) return;
        // Nothing found says so; nothing typed yet shows the recent places, as Telegram's search does.
        final boolean show = searchOpen && searchDone && !searchQuery.isEmpty() && searchResults.isEmpty();
        // Without the region's search data only the map's own names exist, so the page says what to download.
        final boolean noData = mapEngine != null && !mapEngine.hasSearchData();
        searchEmpty.setText(getString(noData ? R.string.PhogramMapsNeedSearchData : R.string.NoResult));
        searchEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** The "Where to?" bar shows over the map, except while a place's card takes its spot. */
    private void updateSearchBar() {
        if (searchBar == null) return;
        final boolean show = mapEngine != null && mapReady && shownPlace == null && !isRouting() && !tools.isMeasuring();
        if (show != (searchBar.getVisibility() == View.VISIBLE)) {
            searchBar.setVisibility(show ? View.VISIBLE : View.GONE);
            updateLayout();
        }
        tools.setRowShown(show);
    }

    private void showPlace(MapEngine.Place place) {
        if (mapEngine == null) return;
        shownPlace = place;
        bindPlaceCard(place);
        // A new place takes the previous one's route off the map.
        mapEngine.clearRoute();
        routeInfo.setVisibility(View.GONE);
        updatePlaceSave();
        placeCard.setVisibility(View.VISIBLE);
        // The same soft pin as the places on the way, in the place's color with its emoji, with a round end.
        placePin.setImageBitmap(MapKinds.pinImage(place.kind));
        placePin.setVisibility(View.INVISIBLE);
        updateSearchBar();
        updateLayout();
        mapEngine.showPlace(place, (x, y) -> {
            if (shownPlace != place) return;
            // The pin's point is the middle of its bottom edge, above the room left for its shadow.
            placePin.setTranslationX(x - placePin.getWidth() / 2f);
            placePin.setTranslationY(y - placePin.getHeight() + MapKinds.pinPadding());
            placePin.setVisibility(View.VISIBLE);
        });
    }

    /** The card's name, "type · distance" and address, and Go with the time there once it is known. */
    private void bindPlaceCard(MapEngine.Place place) {
        placeIcon.setKind(place.kind);
        placeName.setText(place.name);
        final double[] here = mapEngine != null ? mapEngine.getPosition() : null;
        final StringBuilder details = new StringBuilder();
        if (!place.type.isEmpty()) details.append(place.type);
        if (here != null) {
            if (details.length() > 0) details.append(" · ");
            details.append(MapsFormat.distance((float) MapEngine.distance(here[0], here[1], place.latitude, place.longitude)));
        }
        placeDetails.setText(details);
        placeDetails.setVisibility(details.length() > 0 ? View.VISIBLE : View.GONE);
        final boolean hasAddress = place.address != null && !place.address.isEmpty();
        placeAddress.setText(hasAddress ? place.address : "");
        placeAddress.setVisibility(hasAddress ? View.VISIBLE : View.GONE);
        placeRoute.setText(getString(R.string.PhogramNavGo));
        if (mapEngine != null && mapEngine.hasSearchData()) {
            mapEngine.estimate(place, (routes, error) -> {
                if (shownPlace != place || routes.isEmpty()) return;
                placeRoute.setText(LocaleController.formatString(R.string.PhogramNavGoTime, MapsFormat.duration(routes.get(0).seconds)));
            });
        }
    }

    private void hidePlace() {
        shownPlace = null;
        placeCard.setVisibility(View.GONE);
        placePin.setVisibility(View.GONE);
        if (mapEngine != null) {
            mapEngine.showPlace(null, null);
            mapEngine.clearRoute();
        }
        updateSearchBar();
        updateLayout();
    }

    private void sharePlace() {
        final MapEngine.Place place = shownPlace;
        if (place == null || getParentActivity() == null) return;
        // A link any map app or browser opens: OpenStreetMap's page with a marker on the place.
        final String lat = String.format(java.util.Locale.US, "%.6f", place.latitude);
        final String lon = String.format(java.util.Locale.US, "%.6f", place.longitude);
        final String link = "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=17/" + lat + "/" + lon;
        final StringBuilder text = new StringBuilder(place.name);
        if (!android.text.TextUtils.isEmpty(place.address)) {
            text.append('\n').append(place.address);
        }
        text.append('\n').append(link);
        try {
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text.toString());
            getParentActivity().startActivity(Intent.createChooser(intent, getString(R.string.PhogramMapsSharePlace)));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Shown once the map is back after the chat picker; at the latest after the picker's closing animation. */
    private Runnable pendingSentNotice;
    private static final long SENT_NOTICE_DELAY_MS = 450;

    /**
     * Telegram's notice after forwarding, for a place or a trip sent from the map: the forward animation with its tick,
     * "sent to Name" with the chat's name in bold (a tap on it opens the chat), the saved messages animation when sent
     * to oneself, or the number of chats when sent to several.
     */
    private void showSentNotice(java.util.List<MessagesStorage.TopicKey> dids, boolean trip) {
        if (dids == null || dids.isEmpty()) return;
        final int count = dids.size();
        final long did = dids.get(0).dialogId;
        final Runnable[] notice = new Runnable[1];
        notice[0] = () -> {
            if (pendingSentNotice != notice[0]) return;
            pendingSentNotice = null;
            final Activity activity = getParentActivity();
            if (activity == null || fragmentView == null) return;
            final Bulletin.LottieLayout layout = new Bulletin.LottieLayout(activity, getResourceProvider());
            final boolean self = count == 1 && did == getUserConfig().getClientUserId();
            final CharSequence text;
            if (count > 1) {
                text = AndroidUtilities.replaceTags(LocaleController.formatString(
                        trip ? R.string.PhogramNavTripSharedToMany : R.string.PhogramMapsSentToMany, count));
            } else {
                final String name;
                if (self) {
                    name = getString(R.string.SavedMessages);
                } else if (did < 0) {
                    final TLRPC.Chat chat = getMessagesController().getChat(-did);
                    name = chat != null ? chat.title : "";
                } else {
                    name = org.telegram.messenger.UserObject.getFirstName(getMessagesController().getUser(did));
                }
                text = AndroidUtilities.replaceSingleTag(LocaleController.formatString(
                                trip ? R.string.PhogramNavTripSharedTo : R.string.PhogramMapsSentTo, name),
                        -1, AndroidUtilities.REPLACING_TAG_TYPE_LINKBOLD,
                        () -> presentFragment(org.telegram.ui.ChatActivity.of(did)));
            }
            layout.setAnimation(self ? R.raw.saved_messages : R.raw.forward, 30, 30);
            layout.textView.setText(text);
            layout.postDelayed(() -> layout.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING), 300);
            Bulletin.make(this, layout, Bulletin.DURATION_SHORT).show();
        };
        pendingSentNotice = notice[0];
        AndroidUtilities.runOnUIThread(notice[0], SENT_NOTICE_DELAY_MS);
    }

    /** Telegram's chat picker, then the trip's message (where to, the arrival) goes to each picked chat. */
    private void shareTripText(String text) {
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            for (MessagesStorage.TopicKey key : dids) {
                SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(text, key.dialogId,
                        null, null, null, false, null, null, null, notify, scheduleDate, 0, null, false));
            }
            fragment.finishFragment();
            showSentNotice(dids, true);
            return true;
        });
        presentFragment(picker);
    }

    /** Telegram's chat picker, then the place goes to each picked chat as a venue, as Telegram sends one. */
    private void sendPlaceToChat() {
        final MapEngine.Place place = shownPlace;
        if (place == null || sendToTarget(place)) return;
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            for (MessagesStorage.TopicKey key : dids) {
                final TLRPC.TL_messageMediaVenue venue = new TLRPC.TL_messageMediaVenue();
                venue.geo = new TLRPC.TL_geoPoint();
                venue.geo.lat = place.latitude;
                venue.geo._long = place.longitude;
                venue.title = place.name;
                venue.address = place.address == null ? "" : place.address;
                venue.provider = "";
                venue.venue_id = "";
                venue.venue_type = "";
                SendMessagesHelper.getInstance(currentAccount).sendMessage(
                        SendMessagesHelper.SendMessageParams.of(venue, key.dialogId, null, null, null, null, notify, scheduleDate, 0));
            }
            fragment.finishFragment();
            showSentNotice(dids, false);
            return true;
        });
        presentFragment(picker);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        // The tabs page passes back to this tab first: it closes the search, then the place's card.
        if (searchOpen) {
            if (invoked) closeSearch();
            return false;
        }
        if (tools.isMeasuring()) {
            if (invoked) tools.close();
            return false;
        }
        // Then the navigation (back to the preview), then the preview (back to the place's card).
        if (isRouting()) {
            if (invoked) routeUi.onBackPressed();
            return false;
        }
        // On the screen a chat's location opened, back leaves the screen as Telegram's location screen does.
        if (shownPlace != null && hasMainTabs) {
            if (invoked) hidePlace();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private class SearchAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        SearchAdapter(Context context) {
            this.context = context;
        }

        private static final int TYPE_PLACE = 0;
        private static final int TYPE_HEADER = 1;
        private static final int TYPE_MY_PLACES = 2;

        /**
         * Nothing typed: home and work, then the saved places, then the recent ones, each under Telegram's gray header
         * (the recent ones with its Clear button). Something typed: the places found.
         */
        private boolean showsRecent() {
            return searchQuery.isEmpty();
        }

        private int savedRows() {
            return savedPlaces.isEmpty() ? 0 : savedPlaces.size() + 1;
        }

        private int recentRows() {
            return recentPlaces.isEmpty() ? 0 : recentPlaces.size() + 1;
        }

        MapEngine.Place placeAt(int position) {
            if (showsRecent()) {
                position -= 1;
                final int saved = savedRows();
                if (position < 0) return null;
                if (position < saved) {
                    return position >= 1 ? savedPlaces.get(position - 1) : null;
                }
                final int recent = position - saved;
                return recent >= 1 && recent <= recentPlaces.size() ? recentPlaces.get(recent - 1) : null;
            }
            return position >= 0 && position < shownResults && position < searchResults.size() ? searchResults.get(position) : null;
        }

        @Override
        public int getItemCount() {
            if (showsRecent()) {
                return 1 + savedRows() + recentRows();
            }
            return Math.min(shownResults, searchResults.size());
        }

        @Override
        public int getItemViewType(int position) {
            if (!showsRecent()) return TYPE_PLACE;
            if (position == 0) return TYPE_MY_PLACES;
            position -= 1;
            final int saved = savedRows();
            return position == 0 && saved > 0 || position == saved ? TYPE_HEADER : TYPE_PLACE;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_PLACE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view;
            if (viewType == TYPE_HEADER) {
                view = new GraySectionCell(context, getResourceProvider());
            } else if (viewType == TYPE_MY_PLACES) {
                view = new MyPlacesRow(context);
            } else {
                view = new PlaceCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_MY_PLACES) {
                ((MyPlacesRow) holder.itemView).update();
                return;
            }
            if (holder.getItemViewType() == TYPE_HEADER) {
                final GraySectionCell header = (GraySectionCell) holder.itemView;
                if (position == 1 && savedRows() > 0) {
                    header.setText(getString(R.string.PhogramMapsSaved));
                } else {
                    header.setText(getString(R.string.Recent), getString(R.string.ClearButton), v -> clearRecentPlaces());
                }
                return;
            }
            final MapEngine.Place place = placeAt(position);
            if (place == null) return;
            final double[] here = mapEngine != null ? mapEngine.getPosition() : null;
            ((PlaceCell) holder.itemView).setPlace(place, searchQuery, here, position != getItemCount() - 1);
        }
    }

    /** Home and work, as two of Telegram's rounded cards with their icons; an empty one says how to set it. */
    private class MyPlacesRow extends LinearLayout {

        private final TextView[] titles = new TextView[2];
        private final TextView[] subtitles = new TextView[2];
        private final ImageView[] icons = new ImageView[2];
        private final FrameLayout[] cards = new FrameLayout[2];

        MyPlacesRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            if (LocaleController.isRTL) setLayoutDirection(LAYOUT_DIRECTION_RTL);
            setPadding(dp(12), dp(4), dp(12), dp(10));
            final boolean rtl = LocaleController.isRTL;
            for (int i = 0; i < 2; i++) {
                final FrameLayout card = new FrameLayout(context);
                final ImageView icon = new ImageView(context);
                icon.setScaleType(ImageView.ScaleType.CENTER);
                icon.setImageResource(i == 0 ? R.drawable.msg_home : R.drawable.msg_work);
                card.addView(icon, LayoutHelper.createFrame(36, 36, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, rtl ? 0 : 10, 0, rtl ? 10 : 0, 0));
                final TextView title = new TextView(context);
                title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                title.setTypeface(AndroidUtilities.bold());
                title.setSingleLine(true);
                title.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
                title.setText(getString(i == 0 ? R.string.PhogramMapsHome : R.string.PhogramMapsWork));
                card.addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, rtl ? 8 : 54, 9, rtl ? 54 : 8, 0));
                final TextView subtitle = new TextView(context);
                subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                subtitle.setSingleLine(true);
                subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                subtitle.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
                card.addView(subtitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, rtl ? 8 : 54, 31, rtl ? 54 : 8, 9));
                final int index = i;
                card.setOnClickListener(v -> openMyPlace(index));
                ScaleStateListAnimator.apply(card, 0.02f, 1.2f);
                addView(card, LayoutHelper.createLinear(0, 58, 1f, i == 1 ? 4 : 0, 0, i == 0 ? 4 : 0, 0));
                titles[i] = title;
                subtitles[i] = subtitle;
                icons[i] = icon;
                cards[i] = card;
            }
        }

        void update() {
            for (int i = 0; i < 2; i++) {
                final MapEngine.Place place = myPlace(i);
                subtitles[i].setText(place != null ? place.name : getString(i == 0 ? R.string.PhogramMapsAddHome : R.string.PhogramMapsAddWork));
                titles[i].setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                subtitles[i].setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                final int color = getThemedColor(Theme.key_windowBackgroundWhiteGreenText);
                icons[i].setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                final GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor((color & 0x00ffffff) | 0x26000000);
                icons[i].setBackground(circle);
                final GradientDrawable background = new GradientDrawable();
                background.setCornerRadius(dp(14));
                background.setColor(getThemedColor(Theme.key_windowBackgroundGray));
                cards[i].setBackground(background);
            }
        }
    }

    // Home and work: one place each, kept on the phone with the saved ones.

    private static final String HOME_KEY = "home";
    private static final String WORK_KEY = "work";

    private MapEngine.Place myPlace(int index) {
        final ArrayList<MapEngine.Place> places = new ArrayList<>();
        loadPlaces(index == 0 ? HOME_KEY : WORK_KEY, places, 1);
        return places.isEmpty() ? null : places.get(0);
    }

    private void setMyPlace(int index, MapEngine.Place place) {
        final ArrayList<MapEngine.Place> places = new ArrayList<>();
        places.add(place);
        savePlaces(index == 0 ? HOME_KEY : WORK_KEY, places);
        searchAdapter.notifyDataSetChanged();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                getString(index == 0 ? R.string.PhogramMapsHomeSet : R.string.PhogramMapsWorkSet)).show();
    }

    /** Home or work: straight to the ways there; not set yet, a hint how to set it. */
    private void openMyPlace(int index) {
        final MapEngine.Place place = myPlace(index);
        if (place == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                    getString(index == 0 ? R.string.PhogramMapsHomeHint : R.string.PhogramMapsWorkHint)).show();
            return;
        }
        closeSearch();
        showPlace(place);
        showRouteToPlace();
    }

    private void onDownloadAction() {
        if (mapEngine == null) return;
        final MapEngine.Download offer = mapEngine.getOfferedDownload();
        if (offer == null) return;
        if (offer.downloading) {
            mapEngine.cancelOfferedDownload();
        } else {
            mapEngine.startOfferedDownload();
        }
    }

    private void onMapReady() {
        if (contentView == null || mapEngine == null) return;
        progressView.setVisibility(View.GONE);
        mapReady = true;
        locationButton.setVisibility(View.VISIBLE);
        updateSearchBar();
        updateCompass();
        updateDownloadCard();
        if (openedPlace != null) {
            showPlace(openedPlace);
            openedPlace = null;
        } else if (sendTarget != null) {
            // Opened from a chat's attach menu: the card first offers where the user is, as Telegram's does.
            ApplicationLoader.getLocationServiceProvider().getLastLocation(location -> AndroidUtilities.runOnUIThread(() -> {
                if (location == null || mapEngine == null || shownPlace != null) return;
                showPlace(new MapEngine.Place(getString(R.string.SendLocation),
                        String.format(java.util.Locale.US, "%.5f, %.5f", location.getLatitude(), location.getLongitude()),
                        location.getLatitude(), location.getLongitude()));
                myLocationShown = true;
            }));
        }
    }

    /** The chat whose attach menu opened the map; its place card sends there directly. */
    private org.telegram.ui.ChatActivity sendTarget;
    private boolean myLocationShown;

    /**
     * Opens the map for a chat's attach menu, in place of Telegram's location picker on Google's map. False when
     * this build has no map engine, so Telegram's picker opens as before.
     */
    public static boolean openForAttach(org.telegram.ui.Components.ChatAttachAlert alert, BaseFragment fragment) {
        if (!MapEngines.isAvailable() || !(fragment instanceof org.telegram.ui.ChatActivity)) {
            return false;
        }
        final MapsActivity maps = new MapsActivity(new Bundle());
        maps.sendTarget = (org.telegram.ui.ChatActivity) fragment;
        alert.dismiss(true);
        fragment.presentFragment(maps);
        return true;
    }

    /** Sends the shown place to the chat that opened the map: a point for where the user is, else a venue. */
    private boolean sendToTarget(MapEngine.Place place) {
        if (sendTarget == null) {
            return false;
        }
        final TLRPC.MessageMedia media;
        if (myLocationShown && getString(R.string.SendLocation).equals(place.name)) {
            final TLRPC.TL_messageMediaGeo geo = new TLRPC.TL_messageMediaGeo();
            geo.geo = new TLRPC.TL_geoPoint();
            geo.geo.lat = place.latitude;
            geo.geo._long = place.longitude;
            media = geo;
        } else {
            final TLRPC.TL_messageMediaVenue venue = new TLRPC.TL_messageMediaVenue();
            venue.geo = new TLRPC.TL_geoPoint();
            venue.geo.lat = place.latitude;
            venue.geo._long = place.longitude;
            venue.title = place.name;
            venue.address = place.address == null ? "" : place.address;
            venue.provider = "";
            venue.venue_id = "";
            venue.venue_type = "";
            media = venue;
        }
        sendTarget.didSelectLocation(media, 0, true, 0, 0);
        finishFragment();
        return true;
    }

    private void applyMapPadding() {
        if (mapEngine != null) {
            // The map's own marks (OpenStreetMap's credit) sit above the search bar, clear of the floating views.
            final int[] padding = getMapPadding();
            mapEngine.setPadding(padding[0], padding[1], padding[2], padding[3] + dp(12 + SEARCH_BAR_HEIGHT + 8));
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(compassCheck);
        AndroidUtilities.cancelRunOnUIThread(createMapEngine);
        // A trip still going ends with the page: its notification and small window leave too.
        if (routeUi != null) routeUi.release();
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
        topScrim.setVisibility(hasMap ? View.VISIBLE : View.GONE);
        if (!hasMap) {
            locationButton.setVisibility(View.GONE);
        }
        updateCompass();
        updateStatusBar();
    }

    /** The status bar's icons follow the map's colors: dark on the light map, light on the dark one. */
    private void updateStatusBar() {
        if (pageResumed && getParentActivity() != null) {
            AndroidUtilities.setLightStatusBar(getParentActivity().getWindow(), isLightStatusBar());
        }
    }

    /** How much of the top and bottom edges the status bar and the tab bar cover. */
    public int[] getMapPadding() {
        final int bottom = navigationBarHeight + (hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0);
        return new int[] { 0, statusBarHeight, 0, bottom };
    }

    private void updateLayout() {
        if (contentView == null) return;
        final int[] padding = getMapPadding();
        // The empty state and the progress circle center in the part of the page the bars leave free.
        emptyView.setPadding(dp(32), padding[1], dp(32), padding[3]);
        progressView.setTranslationY((padding[1] - padding[3]) / 2f);

        topScrim.getLayoutParams().height = padding[1] + dp(20);
        ((FrameLayout.LayoutParams) backButton.getLayoutParams()).topMargin = padding[1] + dp(8);
        // The download card floats under the status bar, beside the back button when there is one.
        final FrameLayout.LayoutParams card = (FrameLayout.LayoutParams) downloadCard.getLayoutParams();
        ((FrameLayout.LayoutParams) compassStrip.getLayoutParams()).topMargin = padding[1] + dp(4);
        // Under the compass strip.
        card.topMargin = padding[1] + dp(4 + 28 + 6);
        card.leftMargin = backButton.getVisibility() == View.VISIBLE ? dp(60) : dp(8);

        // From the bottom up: the tab bar, the search bar or the place's card, then the round buttons.
        ((FrameLayout.LayoutParams) searchBar.getLayoutParams()).bottomMargin = padding[3] + dp(ROW_GAP);
        tools.layout(padding[3] + dp(ROW_GAP), (padding[1] - padding[3]) / 2f);
        ((FrameLayout.LayoutParams) placeCard.getLayoutParams()).bottomMargin = padding[3] + dp(ROW_GAP);
        int buttons = padding[3] + dp(ROW_GAP);
        if (isRouting()) {
            buttons = routeUi.bottomHeight() + dp(12);
        } else if (placeCard.getVisibility() == View.VISIBLE) {
            buttons = padding[3] + dp(ROW_GAP) + placeCard.getHeight() + dp(12);
        } else if (tools.isMeasuring()) {
            buttons += dp(SEARCH_BAR_HEIGHT) + dp(12);
        }
        // Otherwise "my location" sits in the search bar's row, at its start.
        ((FrameLayout.LayoutParams) locationButton.getLayoutParams()).bottomMargin = buttons;

        // The sheet rises to just under the status bar, and its list scrolls out from under the tab bar.
        ((FrameLayout.LayoutParams) searchSheet.getLayoutParams()).topMargin = padding[1] + dp(8);
        searchList.setPadding(0, 0, 0, padding[3] + dp(8));
        if (routeUi != null) routeUi.layout();
        contentView.requestLayout();
        applyMapPadding();
    }

    private void updateColors() {
        if (contentView == null) return;
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        emptyTitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        emptyText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        progressView.setProgressColor(getThemedColor(Theme.key_progressCircle));
        final boolean dark = Theme.isCurrentThemeDark();
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { dark ? 0x66000000 : 0x99ffffff, 0 }));
        backButton.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(40), getThemedColor(Theme.key_location_actionBackground), getThemedColor(Theme.key_location_actionPressedBackground)));
        backButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_location_actionIcon), PorterDuff.Mode.MULTIPLY));

        // The search bar and its sheet, in the colors of Telegram's cards and sheets.
        final GradientDrawable barBackground = new GradientDrawable();
        barBackground.setCornerRadius(dp(SEARCH_BAR_HEIGHT / 2));
        barBackground.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        searchBar.setBackground(barBackground);
        searchBarIcon.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        searchBarText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        sheetScrim.setBackgroundColor(0x33000000);
        tools.updateColors();
        final GradientDrawable sheetBackground = new GradientDrawable();
        sheetBackground.setCornerRadii(new float[] { dp(14), dp(14), dp(14), dp(14), 0, 0, 0, 0 });
        sheetBackground.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        searchSheet.setBackground(sheetBackground);
        final GradientDrawable handleBackground = new GradientDrawable();
        handleBackground.setCornerRadius(dp(2));
        handleBackground.setColor(getThemedColor(Theme.key_sheet_scrollUp));
        sheetHandle.setBackground(handleBackground);
        searchClose.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        searchClose.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(20)));
        searchClear.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        searchClear.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(20)));
        searchProgress.setProgressColor(getThemedColor(Theme.key_progressCircle));
        searchField.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        searchField.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        searchField.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        for (int i = 0; i < chipsRow.getChildCount(); i++) {
            final LinearLayout tile = (LinearLayout) chipsRow.getChildAt(i);
            ((TextView) tile.getChildAt(1)).setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            tile.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), getThemedColor(Theme.key_windowBackgroundGray), getThemedColor(Theme.key_listSelector)));
        }
        searchEmpty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));

        locationButton.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(48), getThemedColor(Theme.key_location_actionBackground), getThemedColor(Theme.key_location_actionPressedBackground)));
        locationButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_location_actionActiveIcon), PorterDuff.Mode.MULTIPLY));
        final GradientDrawable placeBackground = new GradientDrawable();
        placeBackground.setCornerRadius(dp(14));
        placeBackground.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        placeCard.setBackground(placeBackground);
        placeClose.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        placeClose.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(14)));
        placeShareOut.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        placeShareOut.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(14)));
        placeSave.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        placeSave.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(14)));
        routeInfo.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        // Go is Telegram's filled main button; sending into a chat the outlined one beside it.
        placeRoute.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        placeRoute.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
        final GradientDrawable shareBackground = new GradientDrawable();
        shareBackground.setCornerRadius(dp(8));
        shareBackground.setStroke(dp(1), getThemedColor(Theme.key_featuredStickers_addButton));
        placeSend.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        placeSend.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(14)));
        placeSave.setBackground(shareBackground);
        placeName.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        placeDetails.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        placeAddress.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        final android.graphics.drawable.Drawable go = getParentActivity() != null ? getParentActivity().getResources().getDrawable(R.drawable.filled_directions).mutate() : null;
        if (go != null) {
            go.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_featuredStickers_buttonText), PorterDuff.Mode.SRC_IN));
            go.setBounds(0, 0, dp(22), dp(22));
            placeRoute.setCompoundDrawables(LocaleController.isRTL ? null : go, null, LocaleController.isRTL ? go : null, null);
        }
        if (routeUi != null) routeUi.updateColors();
        // The player strip's colors.
        final GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setCornerRadius(dp(12));
        cardBackground.setColor(getThemedColor(Theme.key_inappPlayerBackground));
        downloadCard.setBackground(cardBackground);
        downloadTitle.setTextColor(getThemedColor(Theme.key_inappPlayerTitle));
        downloadButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_inappPlayerPlayPause), PorterDuff.Mode.MULTIPLY));
        downloadButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_inappPlayerPlayPause) & 0x19ffffff, 1, dp(14)));
        downloadClose.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_inappPlayerClose), PorterDuff.Mode.MULTIPLY));
        downloadClose.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_inappPlayerClose) & 0x19ffffff, 1, dp(14)));
        downloadProgress.setProgressColor(getThemedColor(Theme.key_inappPlayerPlayPause));
        downloadProgress.setBackColor(0);
        // Over the bare map, in the map's own light or dark with a soft halo of the page's color around it.
        compassStrip.setColors(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), getThemedColor(Theme.key_text_RedRegular),
                MapKinds.accent(), getThemedColor(Theme.key_windowBackgroundWhiteGreenText),
                (getThemedColor(Theme.key_windowBackgroundWhite) & 0x00ffffff) | 0xE6000000);
    }

    /** The compass strips, the page's and the trip card's, follow the map's bearing and the user's heading. */
    private void updateCompass() {
        if (compassStrip == null) return;
        final float rotation = mapEngine != null ? mapEngine.getRotation() : 0;
        final float heading = mapEngine != null ? mapEngine.getHeading() : Float.NaN;
        final boolean show = mapView != null && mapReady && !isRouting();
        if (show != (compassStrip.getVisibility() == View.VISIBLE)) {
            compassStrip.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        compassStrip.setBearing(rotation);
        compassStrip.setMarks(heading, Float.NaN);
        if (routeUi != null) routeUi.onCompass(rotation, heading);
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
            if (getParentActivity() != null) gnss.start(getParentActivity());
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
        if (getParentActivity() != null) gnss.start(getParentActivity());
        updateColors();
        // The small window gives the map back before the page wakes it.
        if (routeUi != null) routeUi.onPageShown(true);
        if (mapEngine != null) {
            mapEngine.onResume();
            AndroidUtilities.cancelRunOnUIThread(compassCheck);
            AndroidUtilities.runOnUIThread(compassCheck, COMPASS_CHECK_MS);
        }
        // Bulletins rise above the tab bar, and above the place card while it is shown.
        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                final int bottom = getMapPadding()[3];
                return placeCard != null && placeCard.getVisibility() == View.VISIBLE ? bottom + placeCard.getHeight() + dp(8) : bottom;
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        Bulletin.removeDelegate(this);
        pageResumed = false;
        gnss.stop();
        AndroidUtilities.cancelRunOnUIThread(compassCheck);
        if (searchField != null) {
            AndroidUtilities.hideKeyboard(searchField);
        }
        // The map pauses so it does not use the battery while another tab is open.
        if (mapEngine != null) {
            mapEngine.onPause();
        }
        // With the app gone to the background during a trip, the small window takes the map.
        if (routeUi != null) {
            routeUi.onPageShown(false);
        }
    }

    @Override
    public boolean isLightStatusBar() {
        // Over the map the icons follow its style (light or dark, as the app's theme); before it, the page's color.
        if (mapView != null) {
            return !Theme.isCurrentThemeDark();
        }
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
        updateLayout();
        applyMapPadding();
        return WindowInsetsCompat.CONSUMED;
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        // With a map shown, a swipe moves the map; without one the tabs slide as on any other page.
        return mapView == null;
    }
}
