package org.telegram.cogram.drawings;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The drawings kept on this phone, in Android's own SQLite: layers (folders) and their drawings. Each drawing keeps
 * its shape as GeoJSON and its bounding box, so the map asks only for what it shows; its length and area are kept
 * too, for lists that do not measure again.
 */
public final class DrawingsStore extends SQLiteOpenHelper {

    public static final class Layer {
        public long id;
        public String name;
        public boolean visible;
    }

    private static DrawingsStore instance;
    private final List<Runnable> listeners = new ArrayList<>();

    public static DrawingsStore get() {
        if (instance == null) instance = new DrawingsStore();
        return instance;
    }

    private DrawingsStore() {
        super(ApplicationLoader.applicationContext, "phogram_drawings.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE layers (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, visible INTEGER NOT NULL DEFAULT 1, created INTEGER)");
        db.execSQL("CREATE TABLE features (id TEXT PRIMARY KEY, layer_id INTEGER NOT NULL, kind TEXT NOT NULL, name TEXT, note TEXT,"
                + " color INTEGER, geometry TEXT NOT NULL, min_lat REAL, min_lon REAL, max_lat REAL, max_lon REAL,"
                + " length_m REAL, area_m2 REAL, properties TEXT, source TEXT, created INTEGER, updated INTEGER,"
                + " hidden INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX features_layer ON features (layer_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 2: each drawing shown or hidden on its own; the ones kept before stay shown.
        if (oldVersion < 2) db.execSQL("ALTER TABLE features ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0");
    }

    /** Runs whenever a drawing or a layer changes. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

    // Layers.

    public List<Layer> layers() {
        final List<Layer> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, name, visible FROM layers ORDER BY id", null)) {
            while (c.moveToNext()) {
                final Layer layer = new Layer();
                layer.id = c.getLong(0);
                layer.name = c.getString(1);
                layer.visible = c.getInt(2) != 0;
                out.add(layer);
            }
        }
        return out;
    }

    /** The layer new drawings go to: the first one, "My drawings" made when there is none. */
    public long defaultLayer() {
        final List<Layer> layers = layers();
        return layers.isEmpty() ? addLayer(LocaleController.getString(R.string.PhogramDrawingsTitle)) : layers.get(0).id;
    }

    public long addLayer(String name) {
        final ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("created", System.currentTimeMillis());
        final long id = getWritableDatabase().insert("layers", null, values);
        changed();
        return id;
    }

    public void renameLayer(long id, String name) {
        final ContentValues values = new ContentValues();
        values.put("name", name);
        getWritableDatabase().update("layers", values, "id = ?", new String[] { String.valueOf(id) });
        changed();
    }

    public void setLayerVisible(long id, boolean visible) {
        final ContentValues values = new ContentValues();
        values.put("visible", visible ? 1 : 0);
        getWritableDatabase().update("layers", values, "id = ?", new String[] { String.valueOf(id) });
        changed();
    }

    /** The layer and every drawing in it. */
    public void deleteLayer(long id) {
        final SQLiteDatabase db = getWritableDatabase();
        db.delete("features", "layer_id = ?", new String[] { String.valueOf(id) });
        db.delete("layers", "id = ?", new String[] { String.valueOf(id) });
        changed();
    }

    // Drawings.

    public List<Drawing> drawings(long layerId) {
        return query("layer_id = ?", new String[] { String.valueOf(layerId) });
    }

    /** The drawings shown on the map: not hidden, in a layer shown. */
    public List<Drawing> visible() {
        return query("hidden = 0 AND layer_id IN (SELECT id FROM layers WHERE visible = 1)", null);
    }

    public Drawing find(String id) {
        final List<Drawing> found = query("id = ?", new String[] { id });
        return found.isEmpty() ? null : found.get(0);
    }

    public void save(Drawing drawing) {
        saveAll(java.util.Collections.singletonList(drawing));
    }

    /** In one transaction, as an import brings many. */
    public void saveAll(List<Drawing> drawings) {
        final SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Drawing drawing : drawings) {
                final double[] bounds = drawing.bounds();
                final double[] measure = drawing.measure();
                final ContentValues values = new ContentValues();
                values.put("id", drawing.id);
                values.put("layer_id", drawing.layerId);
                values.put("kind", Drawing.KINDS[drawing.kind]);
                values.put("name", drawing.name);
                values.put("note", drawing.note);
                values.put("color", drawing.color);
                values.put("geometry", DrawingFormats.geometry(drawing).toString());
                values.put("min_lat", bounds[0]);
                values.put("min_lon", bounds[1]);
                values.put("max_lat", bounds[2]);
                values.put("max_lon", bounds[3]);
                values.put("length_m", measure[0]);
                values.put("area_m2", measure[1]);
                values.put("properties", drawing.properties.toString());
                values.put("source", drawing.source);
                values.put("created", drawing.created);
                values.put("updated", drawing.updated);
                values.put("hidden", drawing.hidden ? 1 : 0);
                db.insertWithOnConflict("features", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed();
    }

    public void setHidden(String id, boolean hidden) {
        final ContentValues values = new ContentValues();
        values.put("hidden", hidden ? 1 : 0);
        getWritableDatabase().update("features", values, "id = ?", new String[] { id });
        changed();
    }

    public void delete(String id) {
        getWritableDatabase().delete("features", "id = ?", new String[] { id });
        changed();
    }

    private List<Drawing> query(String where, String[] args) {
        final List<Drawing> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("features",
                new String[] { "id", "layer_id", "kind", "name", "note", "color", "geometry", "properties", "source", "created", "updated", "hidden" },
                where, args, null, null, "created")) {
            while (c.moveToNext()) {
                try {
                    final Drawing drawing = new Drawing();
                    drawing.id = c.getString(0);
                    drawing.layerId = c.getLong(1);
                    drawing.kind = java.util.Arrays.asList(Drawing.KINDS).indexOf(c.getString(2));
                    drawing.name = c.getString(3);
                    drawing.note = c.getString(4);
                    drawing.color = c.getInt(5);
                    drawing.coordinates = DrawingFormats.coordinates(new JSONObject(c.getString(6)));
                    drawing.properties = new JSONObject(c.isNull(7) ? "{}" : c.getString(7));
                    drawing.source = c.getString(8);
                    drawing.created = c.getLong(9);
                    drawing.updated = c.getLong(10);
                    drawing.hidden = c.getInt(11) != 0;
                    out.add(drawing);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        return out;
    }
}
