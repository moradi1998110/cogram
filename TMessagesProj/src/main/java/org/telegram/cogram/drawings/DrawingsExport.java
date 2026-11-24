package org.telegram.cogram.drawings;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.content.FileProvider;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.ItemOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Sends drawings out as a file: the format asked in Telegram's menu, written to the app's cache and handed to
 * Android's share sheet, where a chat in Phogram is one of the ways out.
 */
public final class DrawingsExport {

    private DrawingsExport() {
    }

    /** The formats in Telegram's menu over {@code container}: a page's, or a sheet's so the menu shows on it. */
    public static void ask(ViewGroup container, View anchor, List<Drawing> drawings, String name) {
        final Context context = anchor.getContext();
        ItemOptions.makeOptions(container, null, anchor)
                .add(R.drawable.msg_share, "GeoJSON", () -> share(context, name + ".geojson", "application/geo+json", DrawingFormats.toGeoJson(drawings)))
                .add(R.drawable.msg_share, "KML (Google Earth)", () -> share(context, name + ".kml", "application/vnd.google-earth.kml+xml", DrawingFormats.toKml(drawings, name)))
                .add(R.drawable.msg_share, "GPX", () -> share(context, name + ".gpx", "application/gpx+xml", DrawingFormats.toGpx(drawings)))
                .add(R.drawable.msg_share, LocaleController.getString(R.string.PhogramDrawingsCsv), () -> share(context, name + ".csv", "text/csv", DrawingFormats.toCsv(drawings)))
                .show();
    }

    private static void share(Context context, String fileName, String type, String content) {
        if (context == null) return;
        try {
            final File folder = new File(context.getCacheDir(), "drawings");
            folder.mkdirs();
            final File file = new File(folder, fileName.replaceAll("[\\\\/:*?\"<>|]", "_"));
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            final Uri uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file);
            final Intent intent = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.PhogramDrawingsExport)));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
