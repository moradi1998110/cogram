package org.telegram.cogram;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.core.graphics.PathParser;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The landmarks' symbols, one set for the whole Maps tab: the badges on the map, the search results, the place cards,
 * the categories and the pins along a route all draw the same Maki or Temaki symbol (CC0), white on the category's
 * color. The shapes come from phogram_map/poi_icons.json, which the map-style workflow writes.
 */
final class MapSymbols {

    private static final String ASSET = "phogram_map/poi_icons.json";

    private static JSONObject data;
    private static final Map<String, Path> paths = new HashMap<>();

    private MapSymbols() {}

    /** The file's landmark kinds (Protomaps' kinds) with their symbol and badge color. */
    static synchronized JSONObject kinds() {
        JSONObject all = load();
        return all == null ? null : all.optJSONObject("kinds");
    }

    private static synchronized JSONObject load() {
        if (data != null) return data;
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open(ASSET)) {
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

    /** Whether the named symbol can be drawn. */
    static boolean has(String name) {
        return shape(name) != null;
    }

    /** The symbol's shape in its own units, or null when the file lacks it or Android cannot parse it. */
    private static synchronized Path shape(String name) {
        if (name == null) return null;
        if (paths.containsKey(name)) return paths.get(name);
        Path path = null;
        JSONObject all = load();
        JSONObject icon = all == null || all.optJSONObject("icons") == null ? null : all.optJSONObject("icons").optJSONObject(name);
        if (icon != null) {
            try {
                path = PathParser.createPathFromPathData(icon.optString("d"));
                if (path != null) {
                    path.setFillType(icon.optBoolean("evenOdd") ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
                    float width = (float) icon.optDouble("w", 15);
                    float height = (float) icon.optDouble("h", 15);
                    // Kept in a unit square, centered, so every caller only scales and moves it.
                    float scale = 1f / Math.max(width, height);
                    Matrix matrix = new Matrix();
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(-width * scale / 2f, -height * scale / 2f);
                    path.transform(matrix);
                }
            } catch (RuntimeException e) {
                FileLog.e("cogram: map symbol " + name, e);
                path = null;
            }
        }
        paths.put(name, path);
        return path;
    }

    /** Draws the symbol centered on (cx, cy), size pixels across, in the paint's color. Returns false if it cannot. */
    static boolean draw(Canvas canvas, String name, float cx, float cy, float size, Paint paint) {
        Path unit = shape(name);
        if (unit == null) return false;
        Path path = new Path(unit);
        Matrix matrix = new Matrix();
        matrix.setScale(size, size);
        matrix.postTranslate(cx, cy);
        path.transform(matrix);
        canvas.drawPath(path, paint);
        return true;
    }
}
