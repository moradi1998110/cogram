# Debug builds go through R8 because D8's per-module dexing rejects the engine's Java records.
# Unused code is removed and the rest optimized as in release builds, while names stay as written
# for readable crash reports (the engine's own rules also turn renaming off).
-dontobfuscate
-ignorewarnings
