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
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
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
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.LocationCell;
import org.telegram.ui.Components.AlertsCreator;
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
    private ImageView downloadButton;
    private ImageView downloadClose;
    private LineProgressView downloadProgress;
    private String dismissedDownload;

    private static final long SEARCH_DELAY_MS = 300;
    private ActionBarMenuItem searchItem;
    private RecyclerListView searchList;
    private SearchAdapter searchAdapter;
    private TextView searchEmpty;
    private String searchQuery = "";
    private boolean searchDone;
    private final ArrayList<MapEngine.Place> searchResults = new ArrayList<>();
    private final Runnable searchRunnable = this::runSearch;

    private LinearLayout placeCard;
    private LocationCell placeCell;
    private ImageView placeClose;
    private TextView placeShare;
    private ImageView placePin;
    private MapEngine.Place shownPlace;
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

        // Telegram's header search, as on its location page: the field opens in the header and the results list
        // covers the map until a place is picked.
        final ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(0, R.drawable.outline_header_search, getResourceProvider()).setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                        searchList.setVisibility(View.VISIBLE);
                        updateSearchEmpty();
                    }

                    @Override
                    public void onSearchCollapse() {
                        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
                        searchQuery = "";
                        searchResults.clear();
                        searchAdapter.notifyDataSetChanged();
                        searchItem.setShowSearchProgress(false);
                        searchList.setVisibility(View.GONE);
                        searchEmpty.setVisibility(View.GONE);
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        searchQuery = editText.getText().toString().trim();
                        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
                        if (searchQuery.isEmpty()) {
                            searchResults.clear();
                            searchAdapter.notifyDataSetChanged();
                            searchItem.setShowSearchProgress(false);
                            updateSearchEmpty();
                        } else {
                            searchItem.setShowSearchProgress(true);
                            AndroidUtilities.runOnUIThread(searchRunnable, SEARCH_DELAY_MS);
                        }
                    }
                });
        searchItem.setSearchFieldHint(getString(R.string.Search));
        searchItem.setContentDescription(getString(R.string.Search));
        searchItem.setVisibility(View.GONE);

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

        // The offer of a map file to download, in the strip under the header where Telegram plays voice messages
        // and music: the action on the left, the name in one line, the close button on the right, and a line that
        // fills with the download.
        downloadCard = new FrameLayout(context);
        downloadCard.setVisibility(View.GONE);
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
        contentView.addView(downloadCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP));

        // The pin of the place picked from the search: Telegram's map pin, its point on the place.
        placePin = new ImageView(context);
        placePin.setImageResource(R.drawable.map_pin2);
        placePin.setVisibility(View.GONE);
        contentView.addView(placePin, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));

        // The place card, above the tab bar: Telegram's location row with the place's name and address, and its
        // share button.
        placeCard = new LinearLayout(context);
        placeCard.setOrientation(LinearLayout.VERTICAL);
        placeCard.setTranslationZ(dp(2));
        placeCard.setVisibility(View.GONE);
        placeCard.setClickable(true);
        final FrameLayout placeHeader = new FrameLayout(context);
        placeCell = new LocationCell(context, false, getResourceProvider());
        placeHeader.addView(placeCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 0, 40, 0));
        placeClose = new ImageView(context);
        placeClose.setImageResource(R.drawable.miniplayer_close);
        placeClose.setScaleType(ImageView.ScaleType.CENTER);
        placeClose.setContentDescription(getString(R.string.Close));
        placeClose.setOnClickListener(v -> hidePlace());
        placeHeader.addView(placeClose, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        placeCard.addView(placeHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        placeShare = new TextView(context);
        placeShare.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        placeShare.setTypeface(AndroidUtilities.bold());
        placeShare.setGravity(Gravity.CENTER);
        placeShare.setText(getString(R.string.PhogramMapsSharePlace));
        ScaleStateListAnimator.apply(placeShare, 0.02f, 1.2f);
        placeShare.setOnClickListener(v -> sharePlace());
        placeCard.addView(placeShare, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 14, 2, 14, 14));
        contentView.addView(placeCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 8, 0, 8, 8));

        // The search results, between the header and the tab bar.
        searchList = new RecyclerListView(context, getResourceProvider());
        searchList.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        searchList.setAdapter(searchAdapter = new SearchAdapter(context));
        searchList.setClipToPadding(false);
        searchList.setVisibility(View.GONE);
        searchList.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= searchResults.size()) return;
            final MapEngine.Place place = searchResults.get(position);
            actionBar.closeSearchField();
            showPlace(place);
        });
        searchList.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    AndroidUtilities.hideKeyboard(searchItem.getSearchField());
                }
            }
        });
        contentView.addView(searchList, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));
        searchEmpty = new TextView(context);
        searchEmpty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchEmpty.setGravity(Gravity.CENTER);
        searchEmpty.setPadding(dp(32), 0, dp(32), 0);
        searchEmpty.setVisibility(View.GONE);
        contentView.addView(searchEmpty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));

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
        final boolean show = offer != null && (offer.downloading || !offer.name.equals(dismissedDownload));
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
        mapEngine.search(query, (resultQuery, places) -> {
            if (!resultQuery.equals(searchQuery)) return;
            searchResults.clear();
            searchResults.addAll(places);
            searchDone = true;
            searchAdapter.notifyDataSetChanged();
            searchItem.setShowSearchProgress(false);
            updateSearchEmpty();
        });
        searchDone = false;
    }

    private void updateSearchEmpty() {
        if (searchEmpty == null) return;
        // Nothing found says so; nothing typed yet leaves the list blank, as Telegram's search does.
        final boolean show = searchList.getVisibility() == View.VISIBLE && searchDone && !searchQuery.isEmpty() && searchResults.isEmpty();
        searchEmpty.setText(getString(R.string.NoResult));
        searchEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showPlace(MapEngine.Place place) {
        if (mapEngine == null) return;
        shownPlace = place;
        final TLRPC.TL_messageMediaVenue venue = new TLRPC.TL_messageMediaVenue();
        venue.title = place.name;
        venue.address = place.address;
        venue.icon = "pin";
        placeCell.setLocation(venue, null, 2, false, false);
        placeCard.setVisibility(View.VISIBLE);
        placePin.setVisibility(View.INVISIBLE);
        mapEngine.showPlace(place, (x, y) -> {
            if (shownPlace != place) return;
            // The pin's point is the middle of its bottom edge.
            placePin.setTranslationX(x - placePin.getWidth() / 2f);
            placePin.setTranslationY(y - placePin.getHeight());
            placePin.setVisibility(View.VISIBLE);
        });
    }

    private void hidePlace() {
        shownPlace = null;
        placeCard.setVisibility(View.GONE);
        placePin.setVisibility(View.GONE);
        if (mapEngine != null) {
            mapEngine.showPlace(null, null);
        }
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

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (shownPlace != null) {
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

        @Override
        public int getItemCount() {
            return searchResults.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final LocationCell cell = new LocationCell(context, false, getResourceProvider());
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final MapEngine.Place place = searchResults.get(position);
            final TLRPC.TL_messageMediaVenue venue = new TLRPC.TL_messageMediaVenue();
            venue.title = place.name;
            venue.address = place.address;
            venue.icon = "pin";
            ((LocationCell) holder.itemView).setLocation(venue, null, position, position != searchResults.size() - 1, false);
        }
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
        mapContainer.setVisibility(View.VISIBLE);
        locationButton.setVisibility(View.VISIBLE);
        searchItem.setVisibility(View.VISIBLE);
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
        // The download strip hangs under the header; Telegram's divider and the north button move below it.
        final int strip = downloadCard.getVisibility() == View.VISIBLE ? dp(36) : 0;
        ((FrameLayout.LayoutParams) downloadCard.getLayoutParams()).topMargin = padding[1];
        divider.topMargin = padding[1] + strip;
        ((FrameLayout.LayoutParams) compassButton.getLayoutParams()).topMargin = padding[1] + strip + dp(12);
        // The results start under the header and scroll out from under the tab bar.
        ((FrameLayout.LayoutParams) searchList.getLayoutParams()).topMargin = padding[1];
        searchList.setPadding(0, 0, 0, padding[3]);
        searchEmpty.setPadding(dp(32), padding[1], dp(32), padding[3]);
        ((FrameLayout.LayoutParams) placeCard.getLayoutParams()).bottomMargin = padding[3] + dp(8);
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
        searchList.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        searchEmpty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        final EditTextBoldCursor searchField = searchItem.getSearchField();
        searchField.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        searchField.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        searchField.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        final GradientDrawable placeBackground = new GradientDrawable();
        placeBackground.setCornerRadius(dp(14));
        placeBackground.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        placeCard.setBackground(placeBackground);
        placeClose.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        placeClose.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(14)));
        placeShare.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        placeShare.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
        // The player strip's colors.
        downloadCard.setBackgroundColor(getThemedColor(Theme.key_inappPlayerBackground));
        downloadTitle.setTextColor(getThemedColor(Theme.key_inappPlayerTitle));
        downloadButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_inappPlayerPlayPause), PorterDuff.Mode.MULTIPLY));
        downloadButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_inappPlayerPlayPause) & 0x19ffffff, 1, dp(14)));
        downloadClose.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_inappPlayerClose), PorterDuff.Mode.MULTIPLY));
        downloadClose.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_inappPlayerClose) & 0x19ffffff, 1, dp(14)));
        downloadProgress.setProgressColor(getThemedColor(Theme.key_inappPlayerPlayPause));
        downloadProgress.setBackColor(0);
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
