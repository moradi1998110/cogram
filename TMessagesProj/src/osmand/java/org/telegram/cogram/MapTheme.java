package org.telegram.cogram;

import android.graphics.Color;

import androidx.core.graphics.ColorUtils;

import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;

/**
 * Gives Phogram's map the surfaces of the user's Telegram theme. What every map shares stays as it is (blue water,
 * green parks, orange and yellow main roads), and the neutral surfaces take the theme's own background: the land,
 * the buildings, the small streets and the halo around labels. In the blue "Night" theme the dark map turns the deep
 * blue of its chats, in a white theme it stays a warm light grey. A theme whose colors would leave the labels hard to
 * read keeps the map's own palette.
 */
final class MapTheme {

    /** The style's own land, the fallback and the anchor the theme's background is mixed with. */
    private static final int LIGHT_EARTH = 0xFFF4F1EC;
    private static final int DARK_EARTH = 0xFF1B1F24;
    /** The darkest label on the light map and the lightest on the dark map, which must stay readable. */
    private static final int LIGHT_TEXT = 0xFF383838;
    private static final int DARK_TEXT = 0xFFD0D7DC;
    /** Below this contrast ratio (WCAG's for normal text) a theme's background is not used. */
    private static final double MIN_CONTRAST = 4.5;

    private static final String[] LABEL_LAYERS = {
            "places_locality", "places_subplace", "places_region", "places_country", "roads_labels_major",
            "roads_labels_minor", "water_waterway_label", "water_label_ocean", "water_label_lakes",
            "earth_label_islands", "address_label", "pois",
    };

    private MapTheme() {}

    /** Telegram's accent color, which the route, the user's position and the road plates take. */
    static int accent() {
        return MapKinds.accent();
    }

    static void apply(Style style, boolean dark) {
        try {
            int themeBackground = Theme.getColor(Theme.key_windowBackgroundWhite) | 0xFF000000;
            int anchor = dark ? DARK_EARTH : LIGHT_EARTH;
            int earth = ColorUtils.blendARGB(anchor, themeBackground, dark ? 0.65f : 0.35f);
            if (ColorUtils.calculateContrast(dark ? DARK_TEXT : LIGHT_TEXT, earth) < MIN_CONTRAST
                    || isDark(earth) != dark) {
                return;
            }
            int buildings = ColorUtils.blendARGB(earth, dark ? Color.WHITE : Color.BLACK, 0.07f);
            int minor = dark ? ColorUtils.blendARGB(earth, Color.WHITE, 0.13f) : Color.WHITE;
            int service = dark ? ColorUtils.blendARGB(earth, Color.WHITE, 0.10f) : Color.WHITE;
            int casing = ColorUtils.blendARGB(earth, Color.BLACK, dark ? 0.25f : 0.12f);

            set(style, "background", PropertyFactory.backgroundColor(earth));
            set(style, "earth", PropertyFactory.fillColor(earth));
            set(style, "buildings", PropertyFactory.fillColor(buildings));
            set(style, "roads_minor", PropertyFactory.lineColor(minor));
            set(style, "roads_minor_service", PropertyFactory.lineColor(service));
            set(style, "roads_other", PropertyFactory.lineColor(service));
            set(style, "roads_bridges_minor", PropertyFactory.lineColor(minor));
            set(style, "roads_bridges_other", PropertyFactory.lineColor(service));
            set(style, "roads_minor_casing", PropertyFactory.lineColor(casing));
            set(style, "roads_minor_service_casing", PropertyFactory.lineColor(casing));
            for (String id : LABEL_LAYERS) {
                set(style, id, PropertyFactory.textHaloColor(earth));
            }
        } catch (RuntimeException e) {
            // The map's own palette stays.
            FileLog.e(e);
        }
    }

    private static boolean isDark(int color) {
        return ColorUtils.calculateLuminance(color) < 0.35;
    }

    private static void set(Style style, String id, org.maplibre.android.style.layers.PropertyValue<?> value) {
        Layer layer = style.getLayer(id);
        if (layer != null) {
            layer.setProperties(value);
        }
    }
}
