package org.telegram.messenger;

/**
 * Language detection served Telegram's translation, which the app does not offer. Reporting no
 * support hides the translate buttons and keeps ML Kit's language model out of the APK.
 */
public class LanguageDetector {
    public interface StringCallback {
        void run(String str);
    }
    public interface ExceptionCallback {
        void run(Exception e);
    }

    public static boolean hasSupport() {
        return false;
    }

    public static void detectLanguage(String text, StringCallback onSuccess, ExceptionCallback onFail) {
        detectLanguage(text, onSuccess, onFail, false);
    }

    public static void detectLanguage(String text, StringCallback onSuccess, ExceptionCallback onFail, boolean initializeFirst) {
        if (onFail != null) {
            onFail.run(null);
        }
    }
}
