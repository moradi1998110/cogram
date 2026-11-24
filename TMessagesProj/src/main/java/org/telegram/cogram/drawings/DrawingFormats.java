package org.telegram.cogram.drawings;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Drawings in and out of the files other tools read: GeoJSON (RFC 7946, with the simplestyle names and colors QGIS
 * and web maps show), KML for Google Earth, GPX for GPS devices (an area goes as a closed track) and CSV for the
 * landmarks. A file read is at most 20 MB and 200,000 points, and XML is read without its document type, so it
 * cannot reach outside itself (OWASP's XXE).
 */
public final class DrawingFormats {

    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_POINTS = 200_000;

    private DrawingFormats() {
    }

    // GeoJSON.

    static JSONObject geometry(Drawing drawing) {
        try {
            final JSONArray points = new JSONArray();
            for (int i = 0; i + 1 < drawing.coordinates.length; i += 2) {
                points.put(new JSONArray().put(drawing.coordinates[i + 1]).put(drawing.coordinates[i]));
            }
            final JSONObject geometry = new JSONObject();
            if (drawing.kind == Drawing.POINT) {
                geometry.put("type", "Point").put("coordinates", points.get(0));
            } else if (drawing.kind == Drawing.LINE) {
                geometry.put("type", "LineString").put("coordinates", points);
            } else {
                points.put(points.get(0));
                geometry.put("type", "Polygon").put("coordinates", new JSONArray().put(points));
            }
            return geometry;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A Point's, LineString's or Polygon's outer ring's latitude and longitude pairs; a ring without its closing point. */
    static double[] coordinates(JSONObject geometry) throws JSONException {
        final String type = geometry.getString("type");
        JSONArray points;
        if ("Point".equals(type)) {
            points = new JSONArray().put(geometry.getJSONArray("coordinates"));
        } else if ("Polygon".equals(type)) {
            points = geometry.getJSONArray("coordinates").getJSONArray(0);
        } else {
            points = geometry.getJSONArray("coordinates");
        }
        int count = points.length();
        if ("Polygon".equals(type) && count > 1 && points.getJSONArray(0).toString().equals(points.getJSONArray(count - 1).toString())) {
            count--;
        }
        final double[] out = new double[count * 2];
        for (int i = 0; i < count; i++) {
            out[i * 2] = points.getJSONArray(i).getDouble(1);
            out[i * 2 + 1] = points.getJSONArray(i).getDouble(0);
        }
        return out;
    }

    /** The drawings as a FeatureCollection, the map's own source and the exported file both. */
    public static String toGeoJson(List<Drawing> drawings) {
        try {
            final JSONArray features = new JSONArray();
            for (Drawing drawing : drawings) {
                final JSONObject properties = new JSONObject(drawing.properties.toString());
                properties.put("id", drawing.id);
                properties.put("title", drawing.name);
                properties.put("description", drawing.note);
                properties.put("kind", Drawing.KINDS[drawing.kind]);
                properties.put("stroke", hex(drawing.color));
                if (drawing.kind == Drawing.AREA) properties.put("fill", hex(drawing.color)).put("fill-opacity", 0.2);
                if (drawing.kind == Drawing.POINT) properties.put("marker-color", hex(drawing.color));
                features.put(new JSONObject().put("type", "Feature").put("id", drawing.id)
                        .put("geometry", geometry(drawing)).put("properties", properties));
            }
            return new JSONObject().put("type", "FeatureCollection").put("features", features).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // KML, GPX, CSV out.

    public static String toKml(List<Drawing> drawings, String name) {
        final StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>")
                .append(escape(name)).append("</name>\n");
        for (Drawing drawing : drawings) {
            // KML writes colors as alpha, blue, green, red.
            final int c = drawing.color;
            final String abgr = String.format(Locale.US, "ff%02x%02x%02x", c & 0xff, (c >> 8) & 0xff, (c >> 16) & 0xff);
            out.append("<Placemark><name>").append(escape(drawing.name)).append("</name><description>").append(escape(drawing.note))
                    .append("</description><Style><LineStyle><color>").append(abgr).append("</color><width>3</width></LineStyle>")
                    .append("<PolyStyle><color>33").append(abgr.substring(2)).append("</color></PolyStyle></Style>");
            final String coordinates = kmlCoordinates(drawing);
            if (drawing.kind == Drawing.POINT) {
                out.append("<Point><coordinates>").append(coordinates).append("</coordinates></Point>");
            } else if (drawing.kind == Drawing.LINE) {
                out.append("<LineString><coordinates>").append(coordinates).append("</coordinates></LineString>");
            } else {
                out.append("<Polygon><outerBoundaryIs><LinearRing><coordinates>").append(coordinates)
                        .append("</coordinates></LinearRing></outerBoundaryIs></Polygon>");
            }
            out.append("</Placemark>\n");
        }
        return out.append("</Document></kml>\n").toString();
    }

    public static String toGpx(List<Drawing> drawings) {
        final StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Phogram\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        for (Drawing drawing : drawings) {
            final double[] c = drawing.coordinates;
            if (drawing.kind == Drawing.POINT) {
                out.append(String.format(Locale.US, "<wpt lat=\"%.7f\" lon=\"%.7f\">", c[0], c[1]))
                        .append("<name>").append(escape(drawing.name)).append("</name><desc>").append(escape(drawing.note)).append("</desc></wpt>\n");
                continue;
            }
            out.append("<trk><name>").append(escape(drawing.name)).append("</name><desc>").append(escape(drawing.note)).append("</desc><trkseg>");
            final int count = c.length / 2 + (drawing.kind == Drawing.AREA ? 1 : 0);
            for (int i = 0; i < count; i++) {
                final int j = (i % (c.length / 2)) * 2;
                out.append(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\"/>", c[j], c[j + 1]));
            }
            out.append("</trkseg></trk>\n");
        }
        return out.append("</gpx>\n").toString();
    }

    /** The landmarks only: a table has no room for lines and areas. */
    public static String toCsv(List<Drawing> drawings) {
        final StringBuilder out = new StringBuilder("name,latitude,longitude,note\n");
        for (Drawing drawing : drawings) {
            if (drawing.kind != Drawing.POINT) continue;
            out.append(csv(drawing.name)).append(',')
                    .append(String.format(Locale.US, "%.7f,%.7f", drawing.coordinates[0], drawing.coordinates[1])).append(',')
                    .append(csv(drawing.note)).append('\n');
        }
        return out.toString();
    }

    // In.

    /**
     * The drawings in a GeoJSON, KML or GPX file, told apart by what it holds rather than by its name, which chat
     * apps often drop; their layer and color are the caller's.
     */
    public static List<Drawing> read(InputStream in, int color) throws Exception {
        final String text = readLimited(in);
        final List<Drawing> out = new ArrayList<>();
        final String start = text.substring(0, Math.min(text.length(), 4096)).replace("﻿", "").trim();
        if (start.startsWith("{")) {
            readGeoJson(new JSONObject(text), out, color);
        } else if (start.startsWith("<") && start.contains("<gpx")) {
            readXml(text, out, color, true);
        } else if (start.startsWith("<") && (start.contains("<kml") || start.contains(":kml"))) {
            readXml(text, out, color, false);
        } else {
            throw new IOException("not GeoJSON, KML or GPX");
        }
        int points = 0;
        for (Drawing drawing : out) {
            points += drawing.coordinates.length / 2;
            drawing.source = "imported";
        }
        if (points > MAX_POINTS) throw new IOException("too many points");
        return out;
    }

    private static void readGeoJson(JSONObject root, List<Drawing> out, int color) throws JSONException {
        final String type = root.optString("type");
        if ("FeatureCollection".equals(type)) {
            final JSONArray features = root.getJSONArray("features");
            for (int i = 0; i < features.length(); i++) readGeoJson(features.getJSONObject(i), out, color);
            return;
        }
        final JSONObject geometry = "Feature".equals(type) ? root.optJSONObject("geometry") : root;
        if (geometry == null) return;
        final JSONObject properties = "Feature".equals(type) && root.optJSONObject("properties") != null ? root.getJSONObject("properties") : new JSONObject();
        final String geometryType = geometry.optString("type");
        // Multi geometries become one drawing per part, each with the feature's name and fields.
        if (geometryType.startsWith("Multi")) {
            final JSONArray parts = geometry.getJSONArray("coordinates");
            for (int i = 0; i < parts.length(); i++) {
                final JSONObject part = new JSONObject().put("type", geometryType.substring(5)).put("coordinates", parts.get(i));
                readGeoJson(new JSONObject().put("type", "Feature").put("geometry", part).put("properties", properties), out, color);
            }
            return;
        }
        final int kind = "Point".equals(geometryType) ? Drawing.POINT : "LineString".equals(geometryType) ? Drawing.LINE
                : "Polygon".equals(geometryType) ? Drawing.AREA : -1;
        if (kind < 0) return;
        final double[] coordinates = coordinates(geometry);
        if (!valid(kind, coordinates)) return;
        final Drawing drawing = Drawing.create(kind, coordinates, parseColor(properties, color));
        final String id = properties.optString("id", root.optString("id"));
        if (id.matches("[0-9a-fA-F-]{36}")) drawing.id = id;
        drawing.name = properties.optString("title", properties.optString("name"));
        drawing.note = properties.optString("description", properties.optString("note"));
        final Iterator<String> keys = properties.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (!key.matches("id|title|name|description|note|kind|stroke|stroke-width|stroke-opacity|fill|fill-opacity|marker-color|marker-symbol|marker-size")) {
                drawing.properties.put(key, properties.get(key));
            }
        }
        out.add(drawing);
    }

    /** KML Placemarks (Point, LineString, Polygon's outer ring) or GPX waypoints, tracks and routes. */
    private static void readXml(String text, List<Drawing> out, int color, boolean gpx) throws Exception {
        final XmlPullParser parser = Xml.newPullParser();
        // Android's parser leaves the document type unprocessed by default and never fetches an external entity.
        parser.setInput(new StringReader(text));
        String name = "";
        String note = "";
        String tag = "";
        int kind = -1;
        boolean inner = false;
        final List<Double> points = new ArrayList<>();
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                tag = parser.getName();
                switch (tag) {
                    case "Placemark": case "wpt": case "trk": case "rte":
                        name = "";
                        note = "";
                        break;
                    case "Point": kind = Drawing.POINT; points.clear(); break;
                    case "LineString": case "trkseg": kind = Drawing.LINE; points.clear(); break;
                    case "Polygon": kind = Drawing.AREA; points.clear(); break;
                    case "innerBoundaryIs": inner = true; break;
                    case "trkpt": case "rtept":
                        points.add(Double.parseDouble(parser.getAttributeValue(null, "lat")));
                        points.add(Double.parseDouble(parser.getAttributeValue(null, "lon")));
                        break;
                }
                if (gpx && "wpt".equals(tag)) {
                    kind = Drawing.POINT;
                    points.clear();
                    points.add(Double.parseDouble(parser.getAttributeValue(null, "lat")));
                    points.add(Double.parseDouble(parser.getAttributeValue(null, "lon")));
                }
                if (gpx && "rte".equals(tag)) {
                    kind = Drawing.LINE;
                    points.clear();
                }
            } else if (event == XmlPullParser.TEXT) {
                final String value = parser.getText().trim();
                if (value.isEmpty()) continue;
                if ("name".equals(tag) && name.isEmpty()) name = value;
                else if (("description".equals(tag) || "desc".equals(tag)) && note.isEmpty()) note = value;
                else if ("coordinates".equals(tag) && !inner) {
                    for (String point : value.split("\\s+")) {
                        final String[] parts = point.split(",");
                        if (parts.length < 2) continue;
                        points.add(Double.parseDouble(parts[1]));
                        points.add(Double.parseDouble(parts[0]));
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                final String end = parser.getName();
                tag = "";
                if ("innerBoundaryIs".equals(end)) inner = false;
                if ("Point".equals(end) || "LineString".equals(end) || "Polygon".equals(end)
                        || "wpt".equals(end) || "trkseg".equals(end) || "rte".equals(end)) {
                    double[] coordinates = new double[points.size()];
                    for (int i = 0; i < coordinates.length; i++) coordinates[i] = points.get(i);
                    final boolean closed = coordinates.length > 2 && coordinates[0] == coordinates[coordinates.length - 2]
                            && coordinates[1] == coordinates[coordinates.length - 1];
                    // GPX has no areas: one goes as a closed track, as toGpx writes it and OSM writes a building.
                    if (gpx && "trkseg".equals(end) && closed && coordinates.length >= 8) kind = Drawing.AREA;
                    // A ring's last point repeats its first.
                    if (kind == Drawing.AREA && closed) {
                        coordinates = java.util.Arrays.copyOf(coordinates, coordinates.length - 2);
                    }
                    if (kind >= 0 && valid(kind, coordinates)) {
                        final Drawing drawing = Drawing.create(kind, coordinates, color);
                        drawing.name = name;
                        drawing.note = note;
                        out.add(drawing);
                    }
                    points.clear();
                    kind = -1;
                }
            }
        }
    }

    private static boolean valid(int kind, double[] c) {
        final int needed = kind == Drawing.POINT ? 1 : kind == Drawing.LINE ? 2 : 3;
        if (c.length / 2 < needed) return false;
        for (int i = 0; i + 1 < c.length; i += 2) {
            if (!(Math.abs(c[i]) <= 90) || !(Math.abs(c[i + 1]) <= 180)) return false;
        }
        return true;
    }

    private static String readLimited(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_BYTES) throw new IOException("file too large");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int parseColor(JSONObject properties, int fallback) {
        final String value = properties.optString("stroke", properties.optString("marker-color", properties.optString("fill")));
        if (!value.matches("#[0-9a-fA-F]{6}")) return fallback;
        return 0xff000000 | Integer.parseInt(value.substring(1), 16);
    }

    static String hex(int color) {
        return String.format(Locale.US, "#%06x", color & 0xffffff);
    }

    private static String kmlCoordinates(Drawing drawing) {
        final StringBuilder out = new StringBuilder();
        final double[] c = drawing.coordinates;
        final int count = c.length / 2 + (drawing.kind == Drawing.AREA ? 1 : 0);
        for (int i = 0; i < count; i++) {
            final int j = (i % (c.length / 2)) * 2;
            out.append(String.format(Locale.US, "%.7f,%.7f ", c[j + 1], c[j]));
        }
        return out.toString().trim();
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String csv(String text) {
        if (text == null) return "";
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
