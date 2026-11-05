# Debug builds go through R8 because D8's per-module dexing rejects the engine's Java records.
# Unused code is removed as in release builds, while names and code stay as written for readable crash reports.
-dontobfuscate
-dontoptimize
-ignorewarnings
