package org.telegram.cogram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import org.json.JSONObject;
import org.maplibre.android.maps.Style;
import org.telegram.messenger.FileLog;

import java.util.Iterator;

/**
 * The landmarks' badges on Phogram's map: a round badge in the category's color (Telegram's own colors) with the
 * landmark's symbol in white, as today's maps show them. The symbols are MapSymbols', the same as in the search and
 * on the cards, drawn here at the phone's own density so they stay sharp on every screen. The style asks for them as
 * "pb:" and the landmark's kind.
 */
final class PoiBadges {

    private static final float BADGE_DP = 24;
    private static final float SYMBOL_DP = 13.5f;
    private static final float RING_DP = 1.5f;

    private PoiBadges() {}

    /** Adds every landmark badge to the style; the style shows only the ones its landmarks use. */
    static void addTo(Context context, Style style) {
        JSONObject kinds = MapSymbols.kinds();
        if (kinds == null) return;
        int drawn = 0;
        for (Iterator<String> it = kinds.keys(); it.hasNext(); ) {
            String kind = it.next();
            JSONObject entry = kinds.optJSONObject(kind);
            if (entry == null || !MapSymbols.has(entry.optString("icon"))) {
                FileLog.e("cogram: no symbol for landmark " + kind);
                continue;
            }
            try {
                style.addImage("pb:" + kind, badge(entry.optString("icon"), Color.parseColor(entry.optString("color", "#8E969E"))));
                drawn++;
            } catch (RuntimeException e) {
                FileLog.e("cogram: landmark badge " + kind, e);
            }
        }
        FileLog.d("cogram: " + drawn + " landmark badges");
    }

    private static Bitmap badge(String icon, int color) {
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
        paint.setColor(Color.WHITE);
        MapSymbols.draw(canvas, icon, center, center, MapKinds.dp(SYMBOL_DP), paint);
        return bitmap;
    }
}
