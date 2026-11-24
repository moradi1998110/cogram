package org.telegram.cogram.drawings;

import org.json.JSONObject;
import org.telegram.cogram.measure.Measurement;

import java.util.UUID;

/**
 * Something drawn on the map and kept: a landmark (one point), a line or an area, with its name, note, color and any
 * fields of its own. Its id is a UUID, so a drawing exported and imported again stays the same one.
 */
public final class Drawing {

    public static final int POINT = 0;
    public static final int LINE = 1;
    public static final int AREA = 2;
    static final String[] KINDS = { "point", "line", "area" };

    public String id;
    public long layerId;
    public int kind;
    public String name = "";
    public String note = "";
    public int color;
    /** Latitude and longitude pairs. */
    public double[] coordinates;
    /** Fields of its own, as an imported file gave them or as the user added them. */
    public JSONObject properties = new JSONObject();
    /** "drawn", "imported". */
    public String source = "drawn";
    /** Hidden on the map, its layer shown or not. */
    public boolean hidden;
    public long created;
    public long updated;

    public static Drawing create(int kind, double[] coordinates, int color) {
        final Drawing drawing = new Drawing();
        drawing.id = UUID.randomUUID().toString();
        drawing.kind = kind;
        drawing.coordinates = coordinates;
        drawing.color = color;
        drawing.created = drawing.updated = System.currentTimeMillis();
        return drawing;
    }

    /** {@code {length, area}} in meters and square meters: a line's length, an area's perimeter and size. */
    public double[] measure() {
        if (kind == POINT) return new double[] { 0, 0 };
        return Measurement.of(kind == AREA, coordinates).measure(null);
    }

    /** {@code {minLatitude, minLongitude, maxLatitude, maxLongitude}}. */
    public double[] bounds() {
        final double[] b = { 90, 180, -90, -180 };
        for (int i = 0; i + 1 < coordinates.length; i += 2) {
            b[0] = Math.min(b[0], coordinates[i]);
            b[1] = Math.min(b[1], coordinates[i + 1]);
            b[2] = Math.max(b[2], coordinates[i]);
            b[3] = Math.max(b[3], coordinates[i + 1]);
        }
        return b;
    }
}
