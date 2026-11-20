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
