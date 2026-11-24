package org.telegram.cogram.drawings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The chips over My drawings: all of them, or only landmarks, lines or areas. The one chosen is in the accent color.
 */
final class KindFilter extends LinearLayout {

    /** {@link #ALL} or a {@link Drawing} kind. */
    static final int ALL = -1;

    private static final int[] KINDS = { ALL, Drawing.POINT, Drawing.LINE, Drawing.AREA };
    private static final int[] NAMES = { R.string.PhogramDrawingsAll, R.string.PhogramDrawingsPoints, R.string.PhogramDrawingsLines, R.string.PhogramDrawingsAreas };

    private int chosen = ALL;

    KindFilter(Context context, Utilities.Callback<Integer> onChosen) {
        super(context);
        setPadding(dp(11), dp(10), dp(11), dp(6));
        // Telegram lays out right to left by hand: the chips start from the right in Arabic.
        setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        for (int i = 0; i < KINDS.length; i++) {
            final int index = LocaleController.isRTL ? KINDS.length - 1 - i : i;
            final int kind = KINDS[index];
            final TextView chip = new TextView(context);
            chip.setText(LocaleController.getString(NAMES[index]));
            chip.setTag(kind);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            chip.setTypeface(AndroidUtilities.bold());
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setOnClickListener(v -> {
                chosen = kind;
                update();
                onChosen.run(kind);
            });
            addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 3, 0, 3, 0));
        }
        update();
    }

    int getChosen() {
        return chosen;
    }

    private void update() {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int grey = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        for (int i = 0; i < getChildCount(); i++) {
            final TextView chip = (TextView) getChildAt(i);
            final boolean on = (int) chip.getTag() == chosen;
            chip.setTextColor(on ? accent : grey);
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), Theme.multAlpha(on ? accent : grey, on ? 0.15f : 0.08f), Theme.multAlpha(on ? accent : grey, 0.25f)));
        }
    }
}
