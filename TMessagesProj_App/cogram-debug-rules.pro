# Debug builds go through R8 because D8's per-module dexing rejects the engine's Java records.
# Unused code is removed while names stay as written for readable crash reports (the engine's
# own rules also turn renaming off). R8 does not optimize debuggable builds; letting it optimize
# this build crashed R8 8.13 with an internal error.
-dontobfuscate
-ignorewarnings
