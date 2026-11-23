package org.telegram.cogram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

/**
 * The look of each kind of place, the same in the search, on the cards and on the categories: one of Telegram's
 * emoji on a soft circle in one of Telegram's seven avatar colors.
 */
final class MapKinds {

    private MapKinds() {}

    // Telegram's avatar colors, in the order of Theme.keys_avatar_background.
    private static final int RED = 0, ORANGE = 1, VIOLET = 2, GREEN = 3, CYAN = 4, BLUE = 5, PINK = 6;

    static String emoji(int kind) {
        switch (kind) {
            case MapEngine.KIND_FOOD: return "🍽";
            case MapEngine.KIND_FUEL: return "⛽";
            case MapEngine.KIND_HEALTH: return "🏥";
            case MapEngine.KIND_PHARMACY: return "💊";
            case MapEngine.KIND_WORSHIP: return "🕌";
            case MapEngine.KIND_EDUCATION: return "🎓";
            case MapEngine.KIND_MONEY: return "🏦";
            case MapEngine.KIND_LODGING: return "🏨";
            case MapEngine.KIND_SHOP: return "🛍";
            case MapEngine.KIND_PARKING: return "🅿";
            case MapEngine.KIND_CITY: return "🏙";
            case MapEngine.KIND_STREET: return "🛣";
            case MapEngine.KIND_PIN: return "📌";
            default: return "📍";
        }
    }

    /**
     * The kind's symbol on the map's badges (MapSymbols), so the search, the cards and the categories show the very
     * symbol the map does; null for kinds the map has no badge for, which keep their emoji.
     */
    static String symbol(int kind) {
        switch (kind) {
            case MapEngine.KIND_FOOD: return "restaurant";
            case MapEngine.KIND_FUEL: return "fuel";
            case MapEngine.KIND_HEALTH: return "hospital";
            case MapEngine.KIND_PHARMACY: return "pharmacy";
            case MapEngine.KIND_WORSHIP: return "religious-muslim";
            case MapEngine.KIND_EDUCATION: return "college";
            case MapEngine.KIND_MONEY: return "bank";
            case MapEngine.KIND_LODGING: return "lodging";
            case MapEngine.KIND_SHOP: return "shop";
            case MapEngine.KIND_PARKING: return "parking";
            default: return null;
        }
    }

    /** Whether the kind is drawn as the map's badge (its symbol, white on its color) rather than an emoji. */
    static boolean hasSymbol(int kind) {
        return MapSymbols.has(symbol(kind));
    }

    /** Fuel stations' green, the color of fuel stations' signs, so the most sought landmark on a trip stands out. */
    static final int FUEL_GREEN = 0xFF12A150;

    /** The kind's color: the map badge's for landmarks (Telegram's colors), Telegram's avatar colors for the rest. */
    static int color(int kind) {
        switch (kind) {
            case MapEngine.KIND_FOOD: return 0xFFF28C48;
            case MapEngine.KIND_FUEL: return FUEL_GREEN;
            case MapEngine.KIND_PARKING: return 0xFF5FBED5;
            case MapEngine.KIND_HEALTH: return 0xFFE56555;
            case MapEngine.KIND_PHARMACY: return 0xFFE56555;
            case MapEngine.KIND_WORSHIP: return 0xFF45AE9D;
            case MapEngine.KIND_EDUCATION: return 0xFFC08A5B;
            case MapEngine.KIND_MONEY: return 0xFF3E9DB5;
            case MapEngine.KIND_LODGING: return 0xFFF2749A;
            case MapEngine.KIND_SHOP: return 0xFF549CDD;
        }
        final int index;
        switch (kind) {
            case MapEngine.KIND_CITY: index = BLUE; break;
            case MapEngine.KIND_STREET: index = CYAN; break;
            case MapEngine.KIND_PIN: index = RED; break;
            default: index = BLUE; break;
        }
        return Theme.getColor(Theme.keys_avatar_background[index]);
    }

    /** The kind's mark on a circle of its color: the map's white symbol, or its emoji when it has none. */
    static void drawMark(Canvas canvas, int kind, float cx, float cy, float size) {
        if (hasSymbol(kind)) {
            final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFFFFFFF);
            MapSymbols.draw(canvas, symbol(kind), cx, cy, size * 0.82f, white);
        } else {
            drawEmoji(canvas, emoji(kind), cx, cy, size);
        }
    }

    /**
     * The color of the routes found together, by their place: the best in the user's Telegram accent, the others in
     * two of Telegram's colors that do not look like it. The line on the map and the border of its card share it.
     */
    static int routeColor(int index) {
        if (index == 0) {
            return accent();
        }
        final int[] others = { 0xFF76C84D, 0xFFF28C48, 0xFF8E85EE, 0xFF549CDD };
        int found = 0;
        for (int color : others) {
            if (hueDistance(color, accent()) < 40) continue;
            if (++found == index) return color;
        }
        return others[1];
    }

    /** Telegram's accent color, as the theme the user chose has it. */
    static int accent() {
        return Theme.getColor(Theme.key_featuredStickers_addButton) | 0xFF000000;
    }

    /**
     * The driver's arrow while navigating, as in Waze and Google Maps: a pointed arrow in Telegram's accent with a white
     * border and a soft shadow, pointing up (the map turns it with the heading).
     */
    static android.graphics.Bitmap navigationArrow() {
        return navigationArrow(false);
    }

    /** The driver's arrow; stale, in grey, while the GPS has given no position for a while, as Waze shows it. */
    static android.graphics.Bitmap navigationArrow(boolean stale) {
        final int size = dp(46);
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        final float c = size / 2f;
        final float half = dp(14);
        final android.graphics.Path arrow = new android.graphics.Path();
        arrow.moveTo(c, c - half - dp(3));
        arrow.lineTo(c + half, c + half);
        arrow.lineTo(c, c + half * 0.45f);
        arrow.lineTo(c - half, c + half);
        arrow.close();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setStrokeWidth(dp(6));
        paint.setColor(0xFFFFFFFF);
        paint.setShadowLayer(dp(3), 0, dp(1.5f), 0x55000000);
        canvas.drawPath(arrow, paint);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(stale ? 0xFF9AA0A6 : accent());
        canvas.drawPath(arrow, paint);
        return bitmap;
    }

    /** Text on the accent: white, or near black when the theme's accent is too light for white to read. */
    static int onAccent() {
        return ColorUtils.calculateContrast(0xFFFFFFFF, accent()) >= 3 ? 0xFFFFFFFF : 0xFF1A1A1A;
    }

    /** A deeper shade of the accent, for outlines around it. */
    static int accentDark() {
        return ColorUtils.blendARGB(accent(), 0xFF000000, 0.3f);
    }

    private static float hueDistance(int a, int b) {
        final float[] ha = new float[3];
        final float[] hb = new float[3];
        ColorUtils.colorToHSL(a, ha);
        ColorUtils.colorToHSL(b, hb);
        final float d = Math.abs(ha[0] - hb[0]);
        return Math.min(d, 360 - d);
    }

    // Images the map draws on the road, made once per style from Telegram's emoji and colors.

    static String warningEmoji(int type) {
        switch (type) {
            case MapEngine.Warning.TRAFFIC_LIGHTS: return "🚦";
            case MapEngine.Warning.LEVEL_CROSSING: return "🚆";
            case MapEngine.Warning.SPEED_CAMERA: return "📷";
            case MapEngine.Warning.STOP: return "🛑";
            case MapEngine.Warning.PEDESTRIANS: return "🚸";
            default: return "⚠️";
        }
    }

    /** Telegram's emoji when its picture is loaded, else the system's, centered in the box. */
    static void drawEmoji(android.graphics.Canvas canvas, String code, float cx, float cy, float size) {
        final Drawable drawable = Emoji.getEmojiDrawable(code);
        if (drawable instanceof Emoji.EmojiDrawable && ((Emoji.EmojiDrawable) drawable).isLoaded()) {
            final int half = (int) (size / 2);
            drawable.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
            drawable.draw(canvas);
            return;
        }
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(size * 0.85f);
        text.setTextAlign(Paint.Align.CENTER);
        final Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText(code, cx, cy - (metrics.ascent + metrics.descent) / 2, text);
    }

    /**
     * A hexagon standing on a point, its corners rounded by {@code corner}: one cell of Phogram's mark, the shape of
     * every place on the map.
     */
    static android.graphics.Path hexagon(float cx, float cy, float r, float corner) {
        final float[] xs = new float[6];
        final float[] ys = new float[6];
        for (int i = 0; i < 6; i++) {
            final double angle = Math.toRadians(-90 + 60 * i);
            xs[i] = cx + (float) (Math.cos(angle) * r);
            ys[i] = cy + (float) (Math.sin(angle) * r);
        }
        final float f = Math.min(corner / r, 0.5f);
        final android.graphics.Path path = new android.graphics.Path();
        for (int i = 0; i < 6; i++) {
            final int before = (i + 5) % 6;
            final int after = (i + 1) % 6;
            final float ax = xs[i] + (xs[before] - xs[i]) * f;
            final float ay = ys[i] + (ys[before] - ys[i]) * f;
            if (i == 0) path.moveTo(ax, ay); else path.lineTo(ax, ay);
            path.quadTo(xs[i], ys[i], xs[i] + (xs[after] - xs[i]) * f, ys[i] + (ys[after] - ys[i]) * f);
        }
        path.close();
        return path;
    }

    /** The room around a pin's drawing for its shadow; the pin's point is this far above the bitmap's bottom. */
    static int pinPadding() {
        return dp(4);
    }

    /**
     * A place on the map as a pin with a hexagon head, a cell of Phogram's mark: white with a soft shadow, a short
     * point under it with its end rounded, and inside it a smaller cell in the kind's Telegram color with its emoji.
     */
    static android.graphics.Bitmap pinImage(int kind) {
        final float w = dp(38);
        final float h = dp(48);
        final float pad = pinPadding();
        final int width = (int) (w + pad * 2);
        final int height = (int) (h + pad * 2);
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        final float cx = width / 2f;
        final float r = dp(19);
        final float cy = pad + r;
        final float tip = pad + h;
        final android.graphics.Path pin = hexagon(cx, cy, r, dp(4.5f));
        // The point leaves the head's two lower sides and ends in a small curve, not a sharp corner.
        final android.graphics.Path point = new android.graphics.Path();
        point.moveTo(cx - dp(8), cy + r * 0.5f);
        point.lineTo(cx - dp(1.6f), tip - dp(1.8f));
        point.quadTo(cx, tip, cx + dp(1.6f), tip - dp(1.8f));
        point.lineTo(cx + dp(8), cy + r * 0.5f);
        point.close();
        pin.op(point, android.graphics.Path.Op.UNION);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setShadowLayer(dp(2.5f), 0, dp(1), 0x40000000);
        canvas.drawPath(pin, paint);
        paint.clearShadowLayer();
        paint.setColor(color(kind));
        canvas.drawPath(hexagon(cx, cy, r - dp(3.5f), dp(3.5f)), paint);
        drawMark(canvas, kind, cx, cy, dp(17));
        return bitmap;
    }

    private static final int SIGN_RED = 0xFFD32F2F;
    private static final int SIGN_BLUE = 0xFF1565C0;
    private static final int SIGN_DARK = 0xFF212121;

    /**
     * A warning on the road drawn as its traffic sign, so each is told apart at a glance: the speed camera as the blue
     * camera sign with its radar waves, the traffic lights and the speed bump in the red warning triangle, the railway
     * crossing as its red and white cross, the stop as the red octagon, the pedestrians as the blue crossing sign.
     */
    static android.graphics.Bitmap warningImage(int type) {
        final int size = dp(34);
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float s = size;
        final float c = s / 2f;
        switch (type) {
            case MapEngine.Warning.SPEED_CAMERA: {
                blueSquare(canvas, paint, s);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                // The camera on its post, looking right, with the radar waves in front of the lens.
                final android.graphics.RectF body = new android.graphics.RectF(s * 0.18f, s * 0.36f, s * 0.58f, s * 0.60f);
                canvas.drawRoundRect(body, dp(2), dp(2), paint);
                canvas.drawRect(s * 0.58f, s * 0.41f, s * 0.66f, s * 0.55f, paint);
                canvas.drawRect(s * 0.33f, s * 0.60f, s * 0.40f, s * 0.80f, paint);
                canvas.drawRect(s * 0.25f, s * 0.78f, s * 0.48f, s * 0.83f, paint);
                paint.setColor(SIGN_BLUE);
                canvas.drawCircle(s * 0.30f, s * 0.48f, s * 0.05f, paint);
                paint.setColor(0xFFFFFFFF);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.8f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 2; i++) {
                    final float radius = s * (0.10f + i * 0.09f);
                    canvas.drawArc(new android.graphics.RectF(s * 0.66f - radius, s * 0.48f - radius, s * 0.66f + radius, s * 0.48f + radius),
                            -40, 80, false, paint);
                }
                break;
            }
            case MapEngine.Warning.TRAFFIC_LIGHTS: {
                warningTriangle(canvas, paint, s);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(SIGN_DARK);
                canvas.drawRoundRect(new android.graphics.RectF(c - s * 0.09f, s * 0.36f, c + s * 0.09f, s * 0.80f), dp(2), dp(2), paint);
                final int[] lights = { 0xFFFF3B30, 0xFFFFC107, 0xFF34C759 };
                for (int i = 0; i < 3; i++) {
                    paint.setColor(lights[i]);
                    canvas.drawCircle(c, s * (0.44f + i * 0.13f), s * 0.05f, paint);
                }
                break;
            }
            case MapEngine.Warning.LEVEL_CROSSING: {
                // The railway crossing's cross: two white bars edged in red.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                final float in = s * 0.16f;
                for (int pass = 0; pass < 2; pass++) {
                    paint.setColor(pass == 0 ? SIGN_RED : 0xFFFFFFFF);
                    paint.setStrokeWidth(pass == 0 ? s * 0.22f : s * 0.12f);
                    canvas.drawLine(in, in, s - in, s - in, paint);
                    canvas.drawLine(s - in, in, in, s - in, paint);
                }
                break;
            }
            case MapEngine.Warning.STOP: {
                final android.graphics.Path octagon = new android.graphics.Path();
                for (int i = 0; i < 8; i++) {
                    final double angle = Math.toRadians(22.5 + i * 45);
                    final float x = c + (float) (Math.cos(angle) * (c - dp(1)));
                    final float y = c + (float) (Math.sin(angle) * (c - dp(1)));
                    if (i == 0) octagon.moveTo(x, y); else octagon.lineTo(x, y);
                }
                octagon.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                canvas.drawPath(octagon, paint);
                canvas.save();
                canvas.scale(0.88f, 0.88f, c, c);
                paint.setColor(SIGN_RED);
                canvas.drawPath(octagon, paint);
                canvas.restore();
                final android.text.TextPaint text = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
                text.setColor(0xFFFFFFFF);
                text.setTypeface(AndroidUtilities.bold());
                text.setTextSize(s * 0.25f);
                text.setTextAlign(Paint.Align.CENTER);
                final Paint.FontMetrics metrics = text.getFontMetrics();
                canvas.drawText("STOP", c, c - (metrics.ascent + metrics.descent) / 2, text);
                break;
            }
            case MapEngine.Warning.PEDESTRIANS: {
                blueSquare(canvas, paint, s);
                final android.graphics.Path triangle = new android.graphics.Path();
                triangle.moveTo(c, s * 0.16f);
                triangle.lineTo(s * 0.84f, s * 0.80f);
                triangle.lineTo(s * 0.16f, s * 0.80f);
                triangle.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                paint.setPathEffect(new android.graphics.CornerPathEffect(dp(2)));
                canvas.drawPath(triangle, paint);
                paint.setPathEffect(null);
                drawEmoji(canvas, "🚶", c, s * 0.58f, s * 0.36f);
                break;
            }
            default: {
                // The speed bump: the warning triangle with the bump on the road.
                warningTriangle(canvas, paint, s);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(SIGN_DARK);
                paint.setStrokeWidth(dp(2.2f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                final android.graphics.Path bump = new android.graphics.Path();
                bump.moveTo(s * 0.28f, s * 0.72f);
                bump.lineTo(s * 0.38f, s * 0.72f);
                bump.cubicTo(s * 0.42f, s * 0.52f, s * 0.58f, s * 0.52f, s * 0.62f, s * 0.72f);
                bump.lineTo(s * 0.72f, s * 0.72f);
                canvas.drawPath(bump, paint);
                break;
            }
        }
        return bitmap;
    }

    /** The blue square of information signs, with a thin white edge. */
    private static void blueSquare(android.graphics.Canvas canvas, Paint paint, float s) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        canvas.drawRoundRect(new android.graphics.RectF(0, 0, s, s), dp(6), dp(6), paint);
        paint.setColor(SIGN_BLUE);
        canvas.drawRoundRect(new android.graphics.RectF(dp(1.5f), dp(1.5f), s - dp(1.5f), s - dp(1.5f)), dp(5), dp(5), paint);
    }

    /** The warning triangle: white, with a red edge and rounded corners. */
    private static void warningTriangle(android.graphics.Canvas canvas, Paint paint, float s) {
        final android.graphics.Path triangle = new android.graphics.Path();
        triangle.moveTo(s / 2f, dp(3));
        triangle.lineTo(s - dp(2), s - dp(4));
        triangle.lineTo(dp(2), s - dp(4));
        triangle.close();
        paint.setPathEffect(new android.graphics.CornerPathEffect(dp(3)));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        canvas.drawPath(triangle, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(SIGN_RED);
        canvas.drawPath(triangle, paint);
        paint.setPathEffect(null);
    }

    /**
     * The plate behind road names on the route: a rounded rectangle in Telegram's blue, stretched around the name by
     * the map (its middle stretches, its corners keep their shape).
     */
    static android.graphics.Bitmap namePlate() {
        final int size = dp(28);
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(accent());
        canvas.drawRoundRect(new android.graphics.RectF(0, 0, size, size), dp(9), dp(9), paint);
        return bitmap;
    }

    /** The bubble's size, its tail's width and its corner radius, as in Telegram's message bubbles. */
    static final float BUBBLE_SIZE = 44;
    static final float BUBBLE_TAIL = 7;
    static final float BUBBLE_RADIUS = 14;

    /**
     * A road's or a place's name on the map in the shape of Telegram's incoming message bubble: rounded corners and
     * the curved tail at its bottom-left corner, which points at the road. Colors of the chat theme, with its soft
     * shadow. The map stretches its middle around the name and keeps the corners and the tail.
     */
    static android.graphics.Bitmap bubbleImage() {
        final float size = dp(BUBBLE_SIZE);
        final float tail = dp(BUBBLE_TAIL);
        final float radius = dp(BUBBLE_RADIUS);
        final float shadow = dp(2);
        final int width = (int) (size + tail);
        final int height = (int) (size + shadow);
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        final float left = tail;
        final float right = width;
        final float top = 0;
        final float bottom = size;
        final android.graphics.Path bubble = new android.graphics.Path();
        bubble.moveTo(left + radius, top);
        bubble.lineTo(right - radius, top);
        bubble.quadTo(right, top, right, top + radius);
        bubble.lineTo(right, bottom - radius);
        bubble.quadTo(right, bottom, right - radius, bottom);
        // Telegram's tail: the bottom edge runs out to the tail's point, which curves back up into the left side.
        bubble.lineTo(0, bottom);
        bubble.quadTo(left, bottom - dp(1), left, bottom - dp(8));
        bubble.lineTo(left, top + radius);
        bubble.quadTo(left, top, left + radius, top);
        bubble.close();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Telegram's accent in both modes: the incoming bubble's color was near black on the dark map.
        paint.setColor(accent());
        paint.setShadowLayer(dp(1.5f), 0, dp(1), 0x40000000);
        canvas.drawPath(bubble, paint);
        return bitmap;
    }

    /**
     * The weather at a point of the route, in the shape of the road names' bubble with its tail on the road: what is
     * there on the first line, when the driver gets there under it. A danger is in Telegram's red, a caution in its
     * orange, the rest in the theme's surface.
     */
    static android.graphics.Bitmap weatherBubble(CharSequence what, CharSequence when, int level) {
        final int background;
        final int textColor;
        if (level >= RouteWeather.LEVEL_DANGER) {
            background = 0xFFE56555;
            textColor = 0xFFFFFFFF;
        } else if (level == RouteWeather.LEVEL_CAUTION) {
            background = 0xFFF28C48;
            textColor = 0xFFFFFFFF;
        } else {
            background = Theme.getColor(Theme.key_windowBackgroundWhite);
            textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        }
        final android.text.TextPaint first = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        first.setTextSize(dp(13));
        first.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
        first.setColor(textColor);
        final android.text.TextPaint second = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        second.setTextSize(dp(11));
        second.setColor(ColorUtils.setAlphaComponent(textColor, 0xCC));
        final int firstWidth = (int) Math.ceil(android.text.Layout.getDesiredWidth(what, first));
        final int secondWidth = when == null ? 0 : (int) Math.ceil(android.text.Layout.getDesiredWidth(when, second));
        final int textWidth = Math.max(firstWidth, secondWidth);
        final android.text.StaticLayout line1 = new android.text.StaticLayout(what, first, textWidth,
                android.text.Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
        final android.text.StaticLayout line2 = when == null ? null : new android.text.StaticLayout(when, second, textWidth,
                android.text.Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
        final float tail = dp(BUBBLE_TAIL);
        final float radius = dp(12);
        final float padX = dp(10);
        final float padY = dp(6);
        final float shadow = dp(2);
        final float bodyWidth = textWidth + padX * 2;
        final float bodyHeight = line1.getHeight() + (line2 != null ? line2.getHeight() : 0) + padY * 2;
        final int width = (int) Math.ceil(bodyWidth + tail);
        final int height = (int) Math.ceil(bodyHeight + shadow);
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        final float left = tail;
        final float right = tail + bodyWidth;
        final float bottom = bodyHeight;
        final android.graphics.Path bubble = new android.graphics.Path();
        bubble.moveTo(left + radius, 0);
        bubble.lineTo(right - radius, 0);
        bubble.quadTo(right, 0, right, radius);
        bubble.lineTo(right, bottom - radius);
        bubble.quadTo(right, bottom, right - radius, bottom);
        bubble.lineTo(0, bottom);
        bubble.quadTo(left, bottom - dp(1), left, bottom - dp(8));
        bubble.lineTo(left, radius);
        bubble.quadTo(left, 0, left + radius, 0);
        bubble.close();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(background);
        paint.setShadowLayer(dp(1.5f), 0, dp(1), 0x40000000);
        canvas.drawPath(bubble, paint);
        canvas.save();
        canvas.translate(left + padX, padY);
        line1.draw(canvas);
        if (line2 != null) {
            canvas.translate(0, line1.getHeight());
            line2.draw(canvas);
        }
        canvas.restore();
        return bitmap;
    }

    /** The head of the turn arrow drawn on the road, pointing up before the map turns it. */
    static android.graphics.Bitmap arrowHead() {
        final int size = dp(24);
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path head = new android.graphics.Path();
        head.moveTo(size / 2f, dp(1));
        head.lineTo(size - dp(2), size - dp(3));
        head.lineTo(dp(2), size - dp(3));
        head.close();
        paint.setColor(accentDark());
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(head, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        canvas.drawPath(head, paint);
        return bitmap;
    }

    /**
     * The driver's speed in a ring like Telegram's round progress: the ring fills toward the limit (or 120 km/h), and
     * turns red over the limit; the limit, when the map knows it, sits beside it as a red-ringed sign.
     */
    static final class SpeedView extends View {

        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF arc = new android.graphics.RectF();
        private float speed;
        private float limit;
        /** The speed in Telegram's animated text: its digits roll as it changes. */
        private final org.telegram.ui.Components.AnimatedTextView.AnimatedTextDrawable number =
                new org.telegram.ui.Components.AnimatedTextView.AnimatedTextDrawable(false, true, true);
        private int shownSpeed = -1;
        /** The end of the dial, in km/h. */
        private static final float MAX_KMH = 200;

        SpeedView(Context context) {
            super(context);
            // The gauge's soft shadow is a paint shadow, which needs a software layer.
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(AndroidUtilities.bold());
            unit.setTextAlign(Paint.Align.CENTER);
            number.setCallback(this);
            number.setTypeface(AndroidUtilities.bold());
            number.setGravity(android.view.Gravity.CENTER);
            number.setAnimationProperties(0.35f, 0, 300, org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT_QUINT);
        }

        void set(float speed, float limit) {
            this.speed = Math.max(0, speed);
            final int rounded = Math.round(this.speed);
            if (rounded != shownSpeed) {
                number.setText(String.valueOf(rounded), shownSpeed >= 0 && isLaidOut(), rounded < shownSpeed);
                shownSpeed = rounded;
            }
            this.limit = limit;
            invalidate();
        }

        @Override
        protected boolean verifyDrawable(@androidx.annotation.NonNull Drawable who) {
            return who == number || super.verifyDrawable(who);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final float size = Math.min(getWidth(), getHeight());
            final float r = size / 2f;
            final boolean over = limit > 0 && speed > limit + 2;
            final boolean dark = Theme.isCurrentThemeDark();
            final int surface = Theme.getColor(Theme.key_windowBackgroundWhite) | 0xFF000000;
            // On the dark map the gauge is a step lighter than the map, with a soft shadow, so it stands out.
            fill.setColor(dark ? ColorUtils.blendARGB(surface, 0xFFFFFFFF, 0.10f) : 0xFFFFFFFF);
            fill.setShadowLayer(dp(3), 0, dp(1), dark ? 0x80000000 : 0x40000000);
            canvas.drawCircle(r, r, r - dp(4), fill);
            final float stroke = dp(5);
            ring.setStrokeWidth(stroke);
            arc.set(stroke + dp(4), stroke + dp(4), size - stroke - dp(4), size - stroke - dp(4));
            // The empty part of the ring is the accent, faint, rather than the divider's grey, lost on a dark map.
            ring.setColor(ColorUtils.setAlphaComponent(accent(), dark ? 0x55 : 0x33));
            canvas.drawArc(arc, 135, 270, false, ring);
            // One fixed scale to 200 km/h, so the ring grows with the speed as a car's dial does. It keeps the accent
            // up to the danger mark, the road's limit or else 120, and past it warms through orange to red at the end.
            final float danger = Math.min(MAX_KMH - 10, limit > 0 ? limit : 120);
            final float shown = Math.min(speed, MAX_KMH);
            final float safe = Math.min(shown, danger);
            ring.setColor(accent());
            if (safe > 0) {
                canvas.drawArc(arc, 135, 270 * safe / MAX_KMH, false, ring);
            }
            for (float from = danger; from < shown; from += 4) {
                final float to = Math.min(shown, from + 4);
                final float heat = (to - danger) / (MAX_KMH - danger);
                ring.setColor(heat < 0.5f
                        ? ColorUtils.blendARGB(accent(), 0xFFFB8C00, heat * 2)
                        : ColorUtils.blendARGB(0xFFFB8C00, 0xFFE53935, heat * 2 - 1));
                ring.setStrokeCap(Paint.Cap.BUTT);
                canvas.drawArc(arc, 135 + 270 * from / MAX_KMH, 270 * (to - from) / MAX_KMH + 0.5f, false, ring);
            }
            ring.setStrokeCap(Paint.Cap.ROUND);
            // The road's limit as a short notch across the ring.
            if (limit > 0 && limit < MAX_KMH) {
                final double angle = Math.toRadians(135 + 270 * limit / MAX_KMH);
                final float radius = arc.width() / 2f;
                final float cx = arc.centerX();
                final float cy = arc.centerY();
                final float inner = radius - stroke;
                final float outer = radius + stroke;
                ring.setStrokeWidth(dp(2));
                ring.setColor(0xFFE53935);
                canvas.drawLine(cx + (float) Math.cos(angle) * inner, cy + (float) Math.sin(angle) * inner,
                        cx + (float) Math.cos(angle) * outer, cy + (float) Math.sin(angle) * outer, ring);
                ring.setStrokeWidth(stroke);
            }
            text.setTextSize(size * 0.34f);
            text.setColor(over ? 0xFFE53935 : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            number.setTextSize(size * 0.34f);
            number.setTextColor(text.getColor());
            number.setBounds(0, (int) (r - size * 0.22f), (int) size, (int) (r + size * 0.18f));
            number.draw(canvas);
            unit.setTextSize(size * 0.14f);
            unit.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0xA0));
            canvas.drawText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PhogramNavKmh), r, r + size * 0.3f, unit);
        }
    }

    /** A speed limit sign: the number on white in a red ring. */
    static final class LimitView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int limit;

        LimitView(Context context) {
            super(context);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(AndroidUtilities.bold());
        }

        void setLimit(int limit) {
            if (this.limit == limit) return;
            this.limit = limit;
            setVisibility(limit > 0 ? VISIBLE : GONE);
            invalidate();
        }

        private android.animation.ValueAnimator pulse;

        /** Over the limit the sign beats, growing a little and back, until the speed drops. */
        void setPulsing(boolean pulsing) {
            if (pulsing == (pulse != null)) return;
            if (pulsing) {
                pulse = android.animation.ValueAnimator.ofFloat(1f, 1.18f);
                pulse.setDuration(450);
                pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                pulse.setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_BOTH);
                pulse.addUpdateListener(a -> {
                    final float scale = (float) a.getAnimatedValue();
                    setScaleX(scale);
                    setScaleY(scale);
                });
                pulse.start();
            } else {
                pulse.cancel();
                pulse = null;
                animate().scaleX(1f).scaleY(1f).setDuration(200).start();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            setPulsing(false);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final float size = Math.min(getWidth(), getHeight());
            final float r = size / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFE53935);
            canvas.drawCircle(r, r, r, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(r, r, r * 0.78f, paint);
            paint.setColor(0xFF1A1A1A);
            paint.setTextSize(size * (limit >= 100 ? 0.32f : 0.4f));
            final Paint.FontMetrics metrics = paint.getFontMetrics();
            canvas.drawText(String.valueOf(limit), r, r - (metrics.ascent + metrics.descent) / 2, paint);
        }
    }

    /**
     * A kind's mark on a hexagon cell of Phogram's mark: the map's badge (its white symbol on its color) for
     * landmarks, so the search, the cards and the categories show what the map shows; other kinds keep their emoji on
     * a soft colored cell, redrawn once Telegram's emoji are loaded.
     */
    static final class IconView extends View {

        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.graphics.Path cell;
        private Drawable emoji;
        private int kind = -1;
        private boolean symbol;

        IconView(Context context) {
            super(context);
            white.setColor(0xFFFFFFFF);
            NotificationCenter.listenEmojiLoading(this);
        }

        void setKind(int kind) {
            if (this.kind == kind) return;
            this.kind = kind;
            symbol = hasSymbol(kind);
            emoji = symbol ? null : Emoji.getEmojiDrawable(MapKinds.emoji(kind));
            circle.setColor(symbol ? color(kind) : ColorUtils.setAlphaComponent(color(kind), 0x2E));
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            final float r = Math.min(w, h) / 2f;
            cell = hexagon(w / 2f, h / 2f, r, r * 0.2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            if (cell != null) {
                canvas.drawPath(cell, circle);
            }
            if (symbol) {
                MapSymbols.draw(canvas, symbol(kind), cx, cy, Math.min(getWidth(), getHeight()) * 0.48f, white);
            } else if (emoji != null) {
                final int half = (int) (Math.min(getWidth(), getHeight()) * 0.27f);
                emoji.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
                emoji.draw(canvas);
            }
        }
    }

    /** The turn's sign as a picture, for the trip's notification. */
    static android.graphics.Bitmap turnBitmap(Context context, MapEngine.Maneuver maneuver, int color, int size) {
        final TurnView view = new TurnView(context);
        view.setColor(color);
        view.setTurn(maneuver);
        view.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, size, size);
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** A turn drawn as a road with an arrow, as on a road sign, in one color. */
    static final class TurnView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private int type = MapEngine.Maneuver.STRAIGHT;
        private int exit;

        TurnView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        void setTurn(int type, int exit) {
            setTurn(type, exit, Float.NaN);
        }

        /** The turn drawn at its real angle on the road when known (right positive), else from its kind. */
        void setTurn(int type, int exit, float angle) {
            this.type = type;
            this.exit = exit;
            this.angle = angle;
            invalidate();
        }

        void setTurn(MapEngine.Maneuver maneuver) {
            setTurn(maneuver.type, maneuver.exit, maneuver.angle);
        }

        private float angle = Float.NaN;

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth();
            final float h = getHeight();
            final float s = Math.min(w, h);
            paint.setStrokeWidth(s * 0.11f);
            paint.setStyle(Paint.Style.STROKE);
            canvas.save();
            canvas.translate((w - s) / 2f, (h - s) / 2f);
            if (!Float.isNaN(angle) && type != MapEngine.Maneuver.ARRIVE && type != MapEngine.Maneuver.U_TURN) {
                drawAngle(canvas, s);
                canvas.restore();
                return;
            }
            final boolean left = type == MapEngine.Maneuver.LEFT || type == MapEngine.Maneuver.SLIGHT_LEFT
                    || type == MapEngine.Maneuver.SHARP_LEFT || type == MapEngine.Maneuver.KEEP_LEFT || type == MapEngine.Maneuver.U_TURN;
            if (left) {
                // Left turns are the right ones mirrored.
                canvas.scale(-1, 1, s / 2f, s / 2f);
            }
            path.rewind();
            final float bottom = s * 0.9f;
            final float cx = s * 0.5f;
            float endX, endY, angle;
            switch (type) {
                case MapEngine.Maneuver.ARRIVE: {
                    // The map's pin on its point: a hexagon head over a short point.
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawPath(hexagon(cx, s * 0.36f, s * 0.25f, s * 0.05f), paint);
                    path.moveTo(cx - s * 0.17f, s * 0.46f);
                    path.lineTo(cx, s * 0.88f);
                    path.lineTo(cx + s * 0.17f, s * 0.46f);
                    path.close();
                    canvas.drawPath(path, paint);
                    canvas.restore();
                    return;
                }
                case MapEngine.Maneuver.ROUNDABOUT: {
                    canvas.drawCircle(cx, s * 0.45f, s * 0.2f, paint);
                    path.moveTo(cx, bottom);
                    path.lineTo(cx, s * 0.65f);
                    canvas.drawPath(path, paint);
                    path.rewind();
                    path.moveTo(cx + s * 0.2f, s * 0.45f);
                    path.lineTo(s * 0.88f, s * 0.2f);
                    canvas.drawPath(path, paint);
                    drawHead(canvas, s * 0.88f, s * 0.2f, -35, s);
                    if (exit > 0) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setTextSize(s * 0.24f);
                        paint.setTextAlign(Paint.Align.CENTER);
                        canvas.drawText(String.valueOf(exit), cx, s * 0.53f, paint);
                    }
                    canvas.restore();
                    return;
                }
                case MapEngine.Maneuver.U_TURN:
                    path.moveTo(s * 0.65f, bottom);
                    path.lineTo(s * 0.65f, s * 0.4f);
                    path.quadTo(s * 0.65f, s * 0.15f, s * 0.45f, s * 0.15f);
                    path.quadTo(s * 0.25f, s * 0.15f, s * 0.25f, s * 0.4f);
                    path.lineTo(s * 0.25f, s * 0.6f);
                    canvas.drawPath(path, paint);
                    drawHead(canvas, s * 0.25f, s * 0.62f, 180, s);
                    canvas.restore();
                    return;
                case MapEngine.Maneuver.SLIGHT_LEFT:
                case MapEngine.Maneuver.SLIGHT_RIGHT:
                case MapEngine.Maneuver.KEEP_LEFT:
                case MapEngine.Maneuver.KEEP_RIGHT:
                    endX = s * 0.78f; endY = s * 0.12f; angle = 35;
                    break;
                case MapEngine.Maneuver.LEFT:
                case MapEngine.Maneuver.RIGHT:
                    endX = s * 0.88f; endY = s * 0.38f; angle = 90;
                    break;
                case MapEngine.Maneuver.SHARP_LEFT:
                case MapEngine.Maneuver.SHARP_RIGHT:
                    endX = s * 0.82f; endY = s * 0.72f; angle = 135;
                    break;
                default:
                    endX = cx; endY = s * 0.1f; angle = 0;
                    break;
            }
            final float turnY = angle == 0 ? endY : s * 0.45f;
            path.moveTo(cx, bottom);
            path.lineTo(cx, turnY);
            if (angle != 0) {
                path.lineTo(endX, endY);
            }
            canvas.drawPath(path, paint);
            drawHead(canvas, endX, endY, angle, s);
            canvas.restore();
        }

        /**
         * The turn at its real angle: the road in from below, bending at the junction by the angle; at a roundabout
         * a ring with the road out at that angle and the exit's number.
         */
        private void drawAngle(Canvas canvas, float s) {
            final float cx = s * 0.5f;
            final float bottom = s * 0.92f;
            final float junction = s * 0.55f;
            final double radians = Math.toRadians(angle);
            final float length = s * 0.4f;
            path.rewind();
            if (type == MapEngine.Maneuver.ROUNDABOUT) {
                final float r = s * 0.17f;
                final float cy = s * 0.45f;
                canvas.drawCircle(cx, cy, r, paint);
                path.moveTo(cx, bottom);
                path.lineTo(cx, cy + r);
                canvas.drawPath(path, paint);
                // The way out leaves the ring on the side of its direction.
                final float sx = cx + (float) (Math.sin(radians) * r);
                final float sy = cy - (float) (Math.cos(radians) * r);
                final float ex = cx + (float) (Math.sin(radians) * (r + s * 0.25f));
                final float ey = cy - (float) (Math.cos(radians) * (r + s * 0.25f));
                path.rewind();
                path.moveTo(sx, sy);
                path.lineTo(ex, ey);
                canvas.drawPath(path, paint);
                drawHead(canvas, ex, ey, angle, s);
                if (exit > 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setTextSize(s * 0.2f);
                    paint.setTextAlign(Paint.Align.CENTER);
                    final Paint.FontMetrics metrics = paint.getFontMetrics();
                    canvas.drawText(String.valueOf(exit), cx, cy - (metrics.ascent + metrics.descent) / 2, paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
                return;
            }
            // A gentle bend for small angles, as the road keeps its line; a corner for real turns.
            final float ex = cx + (float) (Math.sin(radians) * length);
            final float ey = junction - (float) (Math.cos(radians) * length);
            path.moveTo(cx, bottom);
            path.lineTo(cx, junction + s * 0.08f);
            path.quadTo(cx, junction, cx + (float) (Math.sin(radians) * s * 0.08f), junction - (float) (Math.cos(radians) * s * 0.08f));
            path.lineTo(ex, ey);
            canvas.drawPath(path, paint);
            drawHead(canvas, ex, ey, angle, s);
        }

        /** An arrowhead at the point, pointing at the angle (0 is up, 90 is right). */
        private void drawHead(Canvas canvas, float x, float y, float angle, float s) {
            final float size = s * 0.2f;
            canvas.save();
            canvas.rotate(angle, x, y);
            final android.graphics.Path head = new android.graphics.Path();
            head.moveTo(x - size, y + size);
            head.lineTo(x, y);
            head.lineTo(x + size, y + size);
            canvas.drawPath(head, paint);
            canvas.restore();
        }
    }

    /** The words for a turn, with the road it leads onto when it has a name. */
    static String describe(MapEngine.Maneuver maneuver) {
        final int text;
        switch (maneuver.type) {
            case MapEngine.Maneuver.SLIGHT_LEFT: text = org.telegram.messenger.R.string.PhogramNavSlightLeft; break;
            case MapEngine.Maneuver.LEFT: text = org.telegram.messenger.R.string.PhogramNavLeft; break;
            case MapEngine.Maneuver.SHARP_LEFT: text = org.telegram.messenger.R.string.PhogramNavSharpLeft; break;
            case MapEngine.Maneuver.SLIGHT_RIGHT: text = org.telegram.messenger.R.string.PhogramNavSlightRight; break;
            case MapEngine.Maneuver.RIGHT: text = org.telegram.messenger.R.string.PhogramNavRight; break;
            case MapEngine.Maneuver.SHARP_RIGHT: text = org.telegram.messenger.R.string.PhogramNavSharpRight; break;
            case MapEngine.Maneuver.KEEP_LEFT: text = org.telegram.messenger.R.string.PhogramNavKeepLeft; break;
            case MapEngine.Maneuver.KEEP_RIGHT: text = org.telegram.messenger.R.string.PhogramNavKeepRight; break;
            case MapEngine.Maneuver.U_TURN: text = org.telegram.messenger.R.string.PhogramNavUTurn; break;
            case MapEngine.Maneuver.ROUNDABOUT: text = org.telegram.messenger.R.string.PhogramNavRoundabout; break;
            case MapEngine.Maneuver.ARRIVE: text = org.telegram.messenger.R.string.PhogramNavArrive; break;
            default: text = org.telegram.messenger.R.string.PhogramNavStraight; break;
        }
        String words = org.telegram.messenger.LocaleController.getString(text);
        if (maneuver.type == MapEngine.Maneuver.ROUNDABOUT && maneuver.exit > 0) {
            words = org.telegram.messenger.LocaleController.formatString(org.telegram.messenger.R.string.PhogramNavRoundaboutExit, maneuver.exit);
        }
        if (!maneuver.street.isEmpty() && maneuver.type != MapEngine.Maneuver.ARRIVE) {
            words = org.telegram.messenger.LocaleController.formatString(org.telegram.messenger.R.string.PhogramNavOnto, words, maneuver.street);
        }
        return words;
    }

    static int dp(float value) {
        return AndroidUtilities.dp(value);
    }
}
