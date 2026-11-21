package org.telegram.cogram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.core.graphics.PathParser;

import org.json.JSONObject;
import org.maplibre.android.maps.Style;
import org.telegram.messenger.FileLog;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * The landmarks' badges on Phogram's map: a round badge in the category's color (food orange, health red, worship
 * teal...) with the landmark's symbol in white, as today's maps show them. The symbols are Maki's and Temaki's shapes
 * (CC0), kept as paths in phogram_map/poi_icons.json and drawn here at the phone's own density, so they stay sharp
 * on every screen. The style asks for them as "pb:" and the landmark's kind.
 */
final class PoiBadges {

    private static final String ASSET = "phogram_map/poi_icons.json";
    private static final float BADGE_DP = 20;
    private static final float SYMBOL_DP = 11;
    private static final float RING_DP = 1.5f;

    private static JSONObject data;

    private PoiBadges() {}

    /** Adds every landmark badge to the style; the style shows only the ones its landmarks use. */
    static void addTo(Context context, Style style) {
        JSONObject all = load(context);
        if (all == null) return;
        JSONObject kinds = all.optJSONObject("kinds");
        JSONObject icons = all.optJSONObject("icons");
        if (kinds == null || icons == null) return;
        for (Iterator<String> it = kinds.keys(); it.hasNext(); ) {
            String kind = it.next();
            JSONObject entry = kinds.optJSONObject(kind);
            JSONObject icon = entry == null ? null : icons.optJSONObject(entry.optString("icon"));
            if (icon == null) continue;
            try {
                style.addImage("pb:" + kind, badge(icon, Color.parseColor(entry.optString("color", "#757575"))));
            } catch (RuntimeException e) {
                FileLog.e("cogram: landmark badge " + kind, e);
            }
        }
    }

    private static synchronized JSONObject load(Context context) {
        if (data != null) return data;
        try (InputStream in = context.getAssets().open(ASSET)) {
            byte[] bytes = new byte[in.available()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            data = new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return data;
    }

    private static Bitmap badge(JSONObject icon, int color) {
        int size = MapKinds.dp(BADGE_DP);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float center = size / 2f;
        // A white ring keeps the badge clear of roads and areas of the same color.
        paint.setColor(Color.WHITE);
        canvas.drawCircle(center, center, center, paint);
        paint.setColor(color);
        canvas.drawCircle(center, center, center - MapKinds.dp(RING_DP), paint);

        Path path = PathParser.createPathFromPathData(icon.optString("d"));
        if (path == null) return bitmap;
        path.setFillType(icon.optBoolean("evenOdd") ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
        float width = (float) icon.optDouble("w", 15);
        float height = (float) icon.optDouble("h", 15);
        float symbol = MapKinds.dp(SYMBOL_DP);
        float scale = symbol / Math.max(width, height);
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(center - width * scale / 2f, center - height * scale / 2f);
        path.transform(matrix);
        paint.setColor(Color.WHITE);
        canvas.drawPath(path, paint);
        return bitmap;
    }
}
