package org.telegram.cogram;

import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A measurement drawn over the map in Telegram's accent: the shape's light fill, its line, its points, and each side's
 * length at its middle, written as the routes' times are.
 */
final class MapMeasureLayers {

    private static final String SOURCE = "phogram-measure";
    private static final String LABEL = "label";

    private FeatureCollection shown = FeatureCollection.fromFeatures(new Feature[0]);
    private GeoJsonSource source;

    /** A new style dropped everything drawn on the old one: the measurement goes on the new one, on top. */
    void attach(Style style) {
        source = new GeoJsonSource(SOURCE, shown);
        style.addSource(source);
        final int accent = MapKinds.accent();
        final Expression side = Expression.has(LABEL);
        style.addLayer(new FillLayer(SOURCE + "-fill", SOURCE)
                .withFilter(Expression.eq(Expression.geometryType(), "Polygon"))
                .withProperties(PropertyFactory.fillColor(accent), PropertyFactory.fillOpacity(0.15f)));
        style.addLayer(new LineLayer(SOURCE + "-line", SOURCE)
                .withFilter(Expression.all(Expression.neq(Expression.geometryType(), "Point"), Expression.not(side)))
                .withProperties(PropertyFactory.lineColor(accent), PropertyFactory.lineWidth(3f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)));
        style.addLayer(new CircleLayer(SOURCE + "-points", SOURCE)
                .withFilter(Expression.eq(Expression.geometryType(), "Point"))
                .withProperties(PropertyFactory.circleRadius(5f), PropertyFactory.circleColor(0xffffffff),
                        PropertyFactory.circleStrokeColor(accent), PropertyFactory.circleStrokeWidth(2.5f)));
        style.addLayer(new SymbolLayer(SOURCE + "-sides", SOURCE)
                .withFilter(side)
                .withProperties(
                        PropertyFactory.textField(Expression.get(LABEL)),
                        PropertyFactory.textFont(new String[] { "Phogram Medium" }),
                        PropertyFactory.textSize(13f),
                        PropertyFactory.textColor(accent),
                        PropertyFactory.textHaloColor(0xffffffff),
                        PropertyFactory.textHaloWidth(2.5f),
                        PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
                        PropertyFactory.textRotationAlignment(Property.TEXT_ROTATION_ALIGNMENT_VIEWPORT),
                        PropertyFactory.textAllowOverlap(true)));
    }

    /**
     * Latitude and longitude pairs, as a line or, from three points, a closed shape, with {@code sides}: each side's
     * length, in the order of {@link org.telegram.cogram.measure.Measurement#sides}. Null clears.
     */
    void show(double[] coordinates, boolean closed, String[] sides) {
        final List<Point> points = new ArrayList<>();
        if (coordinates != null) {
            for (int i = 0; i + 1 < coordinates.length; i += 2) {
                points.add(Point.fromLngLat(coordinates[i + 1], coordinates[i]));
            }
        }
        final List<Feature> features = new ArrayList<>();
        if (closed && points.size() > 2) {
            final List<Point> ring = new ArrayList<>(points);
            ring.add(points.get(0));
            features.add(Feature.fromGeometry(Polygon.fromLngLats(Collections.singletonList(ring))));
        } else if (points.size() > 1) {
            features.add(Feature.fromGeometry(LineString.fromLngLats(points)));
        }
        for (Point point : points) {
            features.add(Feature.fromGeometry(point));
        }
        // Each side on its own, only for its length written at its middle.
        for (int i = 0; sides != null && i < sides.length && points.size() > 1; i++) {
            final Feature side = Feature.fromGeometry(LineString.fromLngLats(Arrays.asList(points.get(i), points.get((i + 1) % points.size()))));
            side.addStringProperty(LABEL, sides[i]);
            features.add(side);
        }
        shown = FeatureCollection.fromFeatures(features);
        if (source != null) source.setGeoJson(shown);
    }
}
