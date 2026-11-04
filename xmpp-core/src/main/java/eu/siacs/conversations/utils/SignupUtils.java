package eu.siacs.conversations.utils;

import android.content.Context;
import android.content.Intent;

/** Stands in for the Conversations onboarding helper, which the Cogram login screen replaces. */
public final class SignupUtils {

    private SignupUtils() {}

    public static boolean isSupportTokenRegistry() {
        return false;
    }

    public static Intent getTokenRegistrationIntent(
            final Context context, final Object address, final String preAuth) {
        return new Intent();
    }

    public static Intent getSignUpIntent(final Context context) {
        return new Intent();
    }
}
