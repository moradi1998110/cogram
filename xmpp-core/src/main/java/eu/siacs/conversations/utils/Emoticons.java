/*
 * Copyright (c) 2017, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.utils;

import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class Emoticons {

    private static final int VARIATION_16 = 0xFE0F;
    private static final int VARIATION_15 = 0xFE0E;
    private static final String VARIATION_16_STRING = new String(new char[] {VARIATION_16});
    private static final String VARIATION_15_STRING = new String(new char[] {VARIATION_15});

    private static final Set<String> TEXT_DEFAULT_TO_VS16 =
            ImmutableSet.of(
                    "❤",
                    "✔",
                    "✖",
                    "➕",
                    "➖",
                    "➗",
                    "⭐",
                    "⚡",
                    "\uD83C\uDF96",
                    "\uD83C\uDFC6",
                    "\uD83E\uDD47",
                    "\uD83E\uDD48",
                    "\uD83E\uDD49",
                    "\uD83D\uDC51",
                    "⚓",
                    "⛵",
                    "✈",
                    "⚖",
                    "⛑",
                    "⚒",
                    "⛏",
                    "☎",
                    "⛄",
                    "⛅",
                    "⚠",
                    "⚛",
                    "✡",
                    "☮",
                    "☯",
                    "☀",
                    "⬅",
                    "➡",
                    "⬆",
                    "⬇");

    public static String normalizeToVS16(final String input) {
        return TEXT_DEFAULT_TO_VS16.contains(input) && !input.endsWith(VARIATION_15_STRING)
                ? input + VARIATION_16_STRING
                : input;
    }

    public static String existingVariant(final String original, final Set<String> existing) {
        if (existing.contains(original) || original.endsWith(VARIATION_15_STRING)) {
            return original;
        }
        final var variant =
                original.endsWith(VARIATION_16_STRING)
                        ? original.substring(0, original.length() - 1)
                        : original + VARIATION_16_STRING;
        return existing.contains(variant) ? variant : original;
    }

    // Phogram checks emoji by Unicode block instead of shipping the jemoji list (about 2 MB). The
    // check accepts every emoji, and also some symbols in the same blocks that are not emoji.
    public static boolean isEmoji(final String input) {
        return input != null && !input.isEmpty() && isOnlyEmoji(input) && hasPictograph(input);
    }

    public static boolean isOnlyEmoji(final String input) {
        final int length = input.length();
        int i = 0;
        while (i < length) {
            final int cp = input.codePointAt(i);
            final int next = i + Character.charCount(cp);
            if (isKeycapBase(cp)) {
                int j = next;
                if (j < length && input.codePointAt(j) == VARIATION_16) {
                    j++;
                }
                if (j >= length || input.codePointAt(j) != KEYCAP) {
                    return false;
                }
            } else if (!isPictograph(cp) && !isEmojiJoiner(cp)) {
                return false;
            }
            i = next;
        }
        return true;
    }

    private static final int KEYCAP = 0x20E3;

    private static boolean hasPictograph(final String input) {
        return input.codePoints().anyMatch(cp -> isPictograph(cp) || cp == KEYCAP);
    }

    private static boolean isKeycapBase(final int cp) {
        return (cp >= '0' && cp <= '9') || cp == '#' || cp == '*';
    }

    private static boolean isEmojiJoiner(final int cp) {
        return cp == 0x200D
                || cp == VARIATION_15
                || cp == VARIATION_16
                || cp == KEYCAP
                || (cp >= 0xE0020 && cp <= 0xE007F);
    }

    private static boolean isPictograph(final int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || (cp >= 0x2190 && cp <= 0x21FF)
                || (cp >= 0x25A0 && cp <= 0x25FF)
                || cp == 0x2934
                || cp == 0x2935
                || cp == 0x00A9
                || cp == 0x00AE
                || cp == 0x203C
                || cp == 0x2049
                || cp == 0x2122
                || cp == 0x2139
                || cp == 0x24C2
                || cp == 0x3030
                || cp == 0x303D
                || cp == 0x3297
                || cp == 0x3299;
    }
}
