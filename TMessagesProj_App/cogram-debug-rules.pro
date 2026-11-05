# Debug builds go through R8 only because D8's per-module dexing rejects the engine's Java records.
-dontobfuscate
-dontshrink
-dontoptimize
-ignorewarnings
