package org.telegram.cogram.gnss;

import android.content.Context;
import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.cogram.measure.MeasureBar;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Locale;

/**
 * What the satellite receiver says, for the ones who read such things: the accuracy, the satellites used of those in
 * view, each system's share and signal, and the position in full. It follows the receiver while open; a tap on the
 * coordinates copies them.
 */
public final class GnssSheet extends BottomSheet implements GnssMonitor.Listener {

    private final GnssMonitor monitor;
    private final LinearLayout rows;
    private final TextView accuracy;
    private final TextView satellites;

    public static void show(Context context, GnssMonitor monitor) {
        new GnssSheet(context, monitor).show();
    }

    private GnssSheet(Context context, GnssMonitor monitor) {
        super(context, false);
        this.monitor = monitor;
        setTitle(LocaleController.getString(R.string.PhogramGnssTitle), true);
        setApplyBottomPadding(false);

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        accuracy = new TextView(context);
        accuracy.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34);
        accuracy.setTypeface(AndroidUtilities.bold());
        accuracy.setGravity(Gravity.CENTER);
        content.addView(accuracy, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        satellites = new TextView(context);
        satellites.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        satellites.setGravity(Gravity.CENTER);
        satellites.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(satellites, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 2, 16, 8));
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        final ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        setCustomView(scroll);

        monitor.addListener(this);
        setOnDismissListener(() -> monitor.removeListener(this));
        onGnssChanged();
    }

    @Override
    public void onGnssChanged() {
        // No satellite fix yet: the network's, in grey and said to be approximate, where the map shows the dot.
        final boolean bySatellites = monitor.getLocation() != null;
        final Location fix = bySatellites ? monitor.getLocation() : monitor.getApproximate();
        accuracy.setText(fix != null && fix.hasAccuracy()
                ? "± " + LocaleController.formatString(R.string.PhogramMapsMeters, Math.round(fix.getAccuracy()))
                : LocaleController.getString(R.string.PhogramGnssNoFix));
        accuracy.setTextColor(Theme.getColor(bySatellites ? GnssGauge.color(monitor.getQuality()) : Theme.key_windowBackgroundWhiteGrayText));
        String summary = monitor.isReported()
                ? LocaleController.formatString(R.string.PhogramGnssUsed, monitor.getUsed(), monitor.getVisible())
                : LocaleController.getString(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? R.string.PhogramGnssSearching : R.string.PhogramGnssUnreported);
        if (!bySatellites && fix != null) summary = LocaleController.getString(R.string.PhogramGnssApproximate) + "\n" + summary;
        if (monitor.isDualFrequency()) summary += "\n" + LocaleController.getString(R.string.PhogramGnssDualFrequency);
        satellites.setText(summary);

        rows.removeAllViews();
        if (!monitor.getSystems().isEmpty()) {
            header(R.string.PhogramGnssSystems);
            for (GnssMonitor.Constellation system : monitor.getSystems()) {
                row(system.name, system.used > 0
                        ? LocaleController.formatString(R.string.PhogramGnssSystemSignal, system.used, system.visible, Math.round(system.signal))
                        : LocaleController.formatString(R.string.PhogramGnssSystemSeen, system.visible));
            }
        }
        if (fix == null) return;
        header(R.string.PhogramGnssPosition);
        final String decimal = MeasureBar.coordinates(fix.getLatitude(), fix.getLongitude());
        row(LocaleController.getString(R.string.PhogramGnssCoordinates), MeasureBar.ltr(decimal)).setOnClickListener(v -> copy(decimal));
        final String degrees = dms(fix.getLatitude(), "N", "S") + "  " + dms(fix.getLongitude(), "E", "W");
        row(LocaleController.getString(R.string.PhogramGnssDegrees), MeasureBar.ltr(degrees)).setOnClickListener(v -> copy(degrees));
        if (fix.hasAltitude()) {
            String altitude = LocaleController.formatString(R.string.PhogramMapsMeters, (int) Math.round(fix.getAltitude()));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && fix.hasVerticalAccuracy()) {
                altitude += "  ± " + LocaleController.formatString(R.string.PhogramMapsMeters, Math.round(fix.getVerticalAccuracyMeters()));
            }
            row(LocaleController.getString(R.string.PhogramGnssAltitude), altitude);
        }
        if (fix.hasSpeed()) {
            row(LocaleController.getString(R.string.PhogramGnssSpeed), LocaleController.formatString(R.string.PhogramUnitKmh, Math.round(fix.getSpeed() * 3.6f)));
        }
        if (fix.hasBearing()) {
            row(LocaleController.getString(R.string.PhogramGnssHeading), Math.round(fix.getBearing()) + "°");
        }
        final long age = (SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1_000_000_000L;
        row(LocaleController.getString(R.string.PhogramGnssFixAge), LocaleController.formatString(R.string.PhogramGnssSecondsAgo, Math.max(0, age)));
    }

    private void header(int text) {
        final HeaderCell cell = new HeaderCell(getContext());
        cell.setText(LocaleController.getString(text));
        rows.addView(cell);
    }

    private TextSettingsCell row(CharSequence name, CharSequence value) {
        final TextSettingsCell cell = new TextSettingsCell(getContext());
        cell.setTextAndValue(name, value, false);
        rows.addView(cell);
        return cell;
    }

    private void copy(String text) {
        AndroidUtilities.addToClipboard(text);
        BulletinFactory.of(getContainer(), null).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
    }

    /** 33°18'45.2"N, as surveys write it. */
    private static String dms(double value, String positive, String negative) {
        final double abs = Math.abs(value);
        final int degrees = (int) abs;
        final int minutes = (int) ((abs - degrees) * 60);
        final double seconds = ((abs - degrees) * 60 - minutes) * 60;
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", degrees, minutes, seconds, value >= 0 ? positive : negative);
    }
}
