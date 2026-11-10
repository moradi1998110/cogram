package eu.siacs.conversations.services;

import org.webrtc.EglBase;

/**
 * Lets the app that embeds the engine show calls itself. While enabled, the engine neither rings
 * nor routes call audio nor plays call tones, because the app's own call screen does all three.
 */
public final class ExternalCallUi {

    public static final int CALLS_EVERYONE = 0;
    public static final int CALLS_CONTACTS = 1;
    public static final int CALLS_NOBODY = 2;

    private static volatile int callPolicy = CALLS_CONTACTS;
    private static volatile boolean enabled;
    private static volatile EglBase.Context sharedEglContext;

    private ExternalCallUi() {}

    /**
     * The app's renderers draw video from the engine, which only works when the engine's GL context
     * shares textures with theirs.
     */
    public static void enable(final EglBase.Context sharedEglContext) {
        ExternalCallUi.sharedEglContext = sharedEglContext;
        enabled = true;
    }

    /** Who may ring us, as the app's privacy settings say. */
    public static void setCallPolicy(final int policy) {
        callPolicy = policy;
    }

    public static int callPolicy() {
        return callPolicy;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static EglBase.Context sharedEglContext() {
        return sharedEglContext;
    }
}
