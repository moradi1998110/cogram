package org.telegram.cogram.measure;

import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.PolygonArea;
import net.sf.geographiclib.PolygonResult;

import java.util.ArrayList;

/**
 * A measurement on the map: the points put down so far, as a line for its length or as a closed shape for its area
 * and perimeter. GeographicLib measures them on the WGS84 ellipsoid, as land surveys do.
 */
public final class Measurement {

    public final boolean area;
    private final ArrayList<double[]> points = new ArrayList<>();

    public Measurement(boolean area) {
        this.area = area;
    }

    /** A measurement of points already known, as latitude and longitude pairs: a saved drawing's. */
    public static Measurement of(boolean area, double[] coordinates) {
        final Measurement measurement = new Measurement(area);
        for (int i = 0; i + 1 < coordinates.length; i += 2) {
            measurement.add(coordinates[i], coordinates[i + 1]);
        }
        return measurement;
    }

    public void add(double latitude, double longitude) {
        points.add(new double[] { latitude, longitude });
    }

    /** Takes the last point back; false when there is none. */
    public boolean undo() {
        if (points.isEmpty()) return false;
        points.remove(points.size() - 1);
        return true;
    }

    public int size() {
        return points.size();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    /**
     * The points as latitude and longitude pairs, then {@code cursor}: the point under the map's center, which the
     * line follows until it is put down. Null without points.
     */
    public double[] coordinates(double[] cursor) {
        if (points.isEmpty()) return null;
        final int count = points.size() + (cursor != null ? 1 : 0);
        final double[] out = new double[count * 2];
        for (int i = 0; i < points.size(); i++) {
            out[i * 2] = points.get(i)[0];
            out[i * 2 + 1] = points.get(i)[1];
        }
        if (cursor != null) {
            out[out.length - 2] = cursor[0];
            out[out.length - 1] = cursor[1];
        }
        return out;
    }

    /**
     * Each side's length in meters, in the order of {@link #coordinates}: from each point to the next, and for an area
     * of three points or more, back from the last to the first.
     */
    public double[] sides(double[] cursor) {
        final double[] c = coordinates(cursor);
        if (c == null) return new double[0];
        final int count = c.length / 2;
        final boolean closing = area && count > 2;
        final double[] out = new double[count - 1 + (closing ? 1 : 0)];
        for (int i = 0; i < out.length; i++) {
            final int j = (i + 1) % count;
            out[i] = Geodesic.WGS84.Inverse(c[i * 2], c[i * 2 + 1], c[j * 2], c[j * 2 + 1]).s12;
        }
        return out;
    }

    /** {@code length}: of the line, or the shape's perimeter, in meters. {@code area}: in square meters. */
    public double[] measure(double[] cursor) {
        final PolygonArea polygon = new PolygonArea(Geodesic.WGS84, !area);
        for (double[] point : points) {
            polygon.AddPoint(point[0], point[1]);
        }
        if (cursor != null && !points.isEmpty()) {
            polygon.AddPoint(cursor[0], cursor[1]);
        }
        final PolygonResult result = polygon.Compute(false, true);
        final double length = result.num > 1 ? result.perimeter : 0;
        final double size = area && result.num > 2 ? Math.abs(result.area) : 0;
        return new double[] { length, size };
    }
}
