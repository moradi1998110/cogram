# OsmAnd's core library (OsmAnd-java), used by the Maps tab for offline search and routing over OsmAnd's map files.
# R8 removes what the search and the routing never reach; these keep what they look up by name.

# OsmAnd's own protobuf 2.5 (moved to net.osmand.protobuf by fetch-osmand.sh) and the map file messages it reads
# through reflection.
-keep class net.osmand.protobuf.** { *; }
-keep class net.osmand.binary.OsmandOdb** { *; }

# The highway hierarchy (HH) routing makes its network points by reflection through their no-argument
# constructors, so those classes keep them.
-keep class net.osmand.router.HHRouteDataStructure** { <init>(...); *; }
-keep class net.osmand.router.HHRoutePlanner** { *; }
-keep class net.osmand.binary.BinaryHHRouteReaderAdapter** { *; }

# The routing rules (routing.xml) name router classes and fields.
-keep class net.osmand.router.GeneralRouter { *; }
-keep class net.osmand.router.GeneralRouter$* { *; }
-keep class net.osmand.router.RoutingConfiguration** { *; }

# Resources the library opens next to its classes (poi_types.xml, routing.xml, rules*.xml, regions.ocbf) are found
# by the class's package, so OsmAnd's classes keep their names and packages (R8 still removes the unused ones).
-keepnames class net.osmand.** { *; }

# Libraries OsmAnd-java builds against for features the Maps tab does not use (OpenStreetMap editing, desktop
# logging, Kotlin multiplatform targets).
-dontwarn com.github.scribejava.**
-dontwarn org.apache.commons.logging.**
-dontwarn org.apache.log4j.**
-dontwarn javax.servlet.**
-dontwarn org.slf4j.**
-dontwarn com.google.gson.**
-dontwarn org.sqlite.**
-dontwarn io.ktor.**
-dontwarn kotlinx.**
-dontwarn coil3.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn net.osmand.**
