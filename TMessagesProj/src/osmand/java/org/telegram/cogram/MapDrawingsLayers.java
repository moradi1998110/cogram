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

/**
 * The drawings kept on the phone, over the map and under a measurement being made: each in its own color, as the
 * GeoJSON the drawings export with gives it (simplestyle's "stroke"), its name where maps write one: under a
 * landmark, along a line's middle, at an area's visual center (MapLibre's, inside even an odd shape).
 */
final class MapDrawingsLayers {

    private static final String SOURCE = "phogram-drawings";
    static final String[] TAPPABLE = { SOURCE + "-fill", SOURCE + "-line", SOURCE + "-points" };

    private String shown = "{\"type\":\"FeatureCollection\",\"features\":[]}";
    private GeoJsonSource source;

    /** A new style dropped everything drawn on the old one: the drawings go on the new one. */
    void attach(Style style) {
        source = new GeoJsonSource(SOURCE, shown);
        style.addSource(source);
        final Expression color = Expression.toColor(Expression.get("stroke"));
        style.addLayer(new FillLayer(SOURCE + "-fill", SOURCE)
                .withFilter(Expression.eq(Expression.geometryType(), "Polygon"))
                .withProperties(PropertyFactory.fillColor(color), PropertyFactory.fillOpacity(0.2f)));
        style.addLayer(new LineLayer(SOURCE + "-line", SOURCE)
                .withFilter(Expression.neq(Expression.geometryType(), "Point"))
                .withProperties(PropertyFactory.lineColor(color), PropertyFactory.lineWidth(3f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)));
        style.addLayer(new CircleLayer(SOURCE + "-points", SOURCE)
                .withFilter(Expression.eq(Expression.geometryType(), "Point"))
                .withProperties(PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(color),
                        PropertyFactory.circleStrokeColor(0xffffffff), PropertyFactory.circleStrokeWidth(2.5f)));
        style.addLayer(names("Point", color).withProperties(
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP), PropertyFactory.textOffset(new Float[] { 0f, 0.9f })));
        style.addLayer(names("LineString", color).withProperties(
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER)));
        style.addLayer(names("Polygon", color));
    }

    /** The names of one kind of shape, in the drawing's color with a white halo. */
    private static SymbolLayer names(String type, Expression color) {
        return new SymbolLayer(SOURCE + "-names-" + type, SOURCE)
                .withFilter(Expression.eq(Expression.geometryType(), type))
                .withProperties(
                        PropertyFactory.textField(Expression.get("title")),
                        PropertyFactory.textFont(new String[] { "Phogram Medium" }),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textColor(color),
                        PropertyFactory.textHaloColor(0xffffffff),
                        PropertyFactory.textHaloWidth(2f));
    }

    /** The drawings as a GeoJSON FeatureCollection. */
    void show(String featureCollection) {
        shown = featureCollection;
        if (source != null) source.setGeoJson(featureCollection);
    }
}
