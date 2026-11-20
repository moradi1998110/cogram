# Phogram's production builds (releases sent to the organization's phones). With Telegram's rules, the engines'
# own rules (xmpp-core's consumer rules, which also keep names as written) and osmand-rules.pro.

# No logging in production: the debug, verbose and info calls are removed with the strings they build, so nothing of
# the user's (addresses, places, messages) reaches the system log. Warnings and errors stay for real faults.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Classes referenced only by libraries for features the app does not use; the debug build ignores the same warnings.
-ignorewarnings

# Readable stack traces from production crashes with the mapping file kept on GitHub.
-keepattributes SourceFile,LineNumberTable
