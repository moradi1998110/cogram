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

    static int color(int kind) {
        final int index;
        switch (kind) {
            case MapEngine.KIND_FOOD: index = ORANGE; break;
            case MapEngine.KIND_FUEL: index = RED; break;
            case MapEngine.KIND_HEALTH: index = RED; break;
            case MapEngine.KIND_PHARMACY: index = GREEN; break;
            case MapEngine.KIND_WORSHIP: index = GREEN; break;
            case MapEngine.KIND_EDUCATION: index = VIOLET; break;
            case MapEngine.KIND_MONEY: index = CYAN; break;
            case MapEngine.KIND_LODGING: index = PINK; break;
            case MapEngine.KIND_SHOP: index = VIOLET; break;
            case MapEngine.KIND_PARKING: index = BLUE; break;
            case MapEngine.KIND_CITY: index = BLUE; break;
            case MapEngine.KIND_STREET: index = CYAN; break;
            case MapEngine.KIND_PIN: index = RED; break;
            default: index = BLUE; break;
        }
        return Theme.getColor(Theme.keys_avatar_background[index]);
    }

    /**
     * The color of the routes found together, by their place: the best in green, the others in Telegram's blue and in
     * orange. The line on the map and the border of its card share it.
     */
    static int routeColor(int index) {
        switch (index) {
            case 0: return 0xFF2EB85C;
            case 1: return 0xFF3A8EE6;
            default: return 0xFFF2994A;
        }
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
    private static void drawEmoji(android.graphics.Canvas canvas, String code, float cx, float cy, float size) {
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

    /** A place on the way: its emoji on a circle in its kind's color with a white ring and a point down, as a pin. */
    static android.graphics.Bitmap pinImage(int kind) {
        final int size = dp(40);
        final int height = size + dp(8);
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, height, android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float r = size / 2f;
        paint.setColor(0x33000000);
        canvas.drawCircle(r, r + dp(1), r - dp(1), paint);
        final android.graphics.Path point = new android.graphics.Path();
        point.moveTo(r - dp(7), size - dp(6));
        point.lineTo(r, height);
        point.lineTo(r + dp(7), size - dp(6));
        point.close();
        paint.setColor(0xFFFFFFFF);
        canvas.drawPath(point, paint);
        canvas.drawCircle(r, r, r - dp(2), paint);
        paint.setColor(color(kind));
        canvas.drawCircle(r, r, r - dp(4.5f), paint);
        drawEmoji(canvas, emoji(kind), r, r, dp(20));
        return bitmap;
    }

    /** A warning on the road: its emoji on a white circle with a red ring, as a road sign. */
    static android.graphics.Bitmap warningImage(int type) {
        final int size = dp(30);
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float r = size / 2f;
        paint.setColor(0xFFE53935);
        canvas.drawCircle(r, r, r - dp(1), paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(r, r, r - dp(3.5f), paint);
        drawEmoji(canvas, warningEmoji(type), r, r, dp(16));
        return bitmap;
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
        paint.setColor(0xFF2B6CB8);
        canvas.drawRoundRect(new android.graphics.RectF(0, 0, size, size), dp(9), dp(9), paint);
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
        paint.setColor(0xFF1F4E8C);
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

        SpeedView(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(AndroidUtilities.bold());
            unit.setTextAlign(Paint.Align.CENTER);
        }

        void set(float speed, float limit) {
            this.speed = Math.max(0, speed);
            this.limit = limit;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final float size = Math.min(getWidth(), getHeight());
            final float r = size / 2f;
            final boolean over = limit > 0 && speed > limit + 2;
            fill.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            canvas.drawCircle(r, r, r - dp(1), fill);
            final float stroke = dp(4);
            ring.setStrokeWidth(stroke);
            arc.set(stroke, stroke, size - stroke, size - stroke);
            ring.setColor(Theme.getColor(Theme.key_divider));
            canvas.drawArc(arc, 135, 270, false, ring);
            final float top = limit > 0 ? limit : 120;
            ring.setColor(over ? 0xFFE53935 : Theme.getColor(Theme.key_featuredStickers_addButton));
            canvas.drawArc(arc, 135, 270 * Math.min(1, speed / top), false, ring);
            text.setTextSize(size * 0.34f);
            text.setColor(over ? 0xFFE53935 : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            canvas.drawText(String.valueOf(Math.round(speed)), r, r + size * 0.1f, text);
            unit.setTextSize(size * 0.15f);
            unit.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
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

    /** A kind's emoji on its soft colored circle; it redraws itself once Telegram's emoji are loaded. */
    static final class IconView extends View {

        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Drawable emoji;
        private int kind = -1;

        IconView(Context context) {
            super(context);
            NotificationCenter.listenEmojiLoading(this);
        }

        void setKind(int kind) {
            if (this.kind == kind) return;
            this.kind = kind;
            emoji = Emoji.getEmojiDrawable(MapKinds.emoji(kind));
            circle.setColor(ColorUtils.setAlphaComponent(color(kind), 0x2E));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            canvas.drawCircle(cx, cy, Math.min(cx, cy), circle);
            if (emoji != null) {
                final int half = (int) (Math.min(getWidth(), getHeight()) * 0.27f);
                emoji.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
                emoji.draw(canvas);
            }
        }
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
            this.type = type;
            this.exit = exit;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth();
            final float h = getHeight();
            final float s = Math.min(w, h);
            paint.setStrokeWidth(s * 0.11f);
            paint.setStyle(Paint.Style.STROKE);
            canvas.save();
            canvas.translate((w - s) / 2f, (h - s) / 2f);
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
                    // A pin on its point.
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, s * 0.38f, s * 0.22f, paint);
                    path.moveTo(cx - s * 0.2f, s * 0.48f);
                    path.lineTo(cx, s * 0.88f);
                    path.lineTo(cx + s * 0.2f, s * 0.48f);
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
