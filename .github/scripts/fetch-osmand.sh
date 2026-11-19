#!/bin/bash
# Fetches OsmAnd's core library (OsmAnd-java, pure Java, with OsmAnd-shared) next to Phogram for the Maps tab's
# offline search and routing over OsmAnd's map files. MapLibre draws the map, so OsmAnd's app, its renderer and its
# native core are not fetched. settings.gradle adds the two modules to the build when osmand/android/OsmAnd-java
# exists. OsmAnd-java reads its routing, POI and abbreviation rules from OsmAnd's resources repository at build time.
set -e
OSMAND_COMMIT=b8136d9c410350ccf6e31ff027f52bae9bd9a9cb
mkdir -p osmand && cd osmand
git init -q android
git -C android fetch -q --depth 1 https://github.com/osmandapp/OsmAnd.git "$OSMAND_COMMIT"
git -C android checkout -q FETCH_HEAD
git clone -q --depth 1 https://github.com/osmandapp/OsmAnd-resources.git resources
for d in android resources; do
  echo "$d $(git -C $d rev-parse HEAD)"
done
cd ..
# OsmAnd's build scripts read their SDK versions from the root project.
cp osmand/android/versions.gradle versions.gradle
# OsmAnd carries its own patched copy of protobuf 2.5 under Google's class names, which clash with the protobuf
# the Signal library behind OMEMO is built on. Moving OsmAnd's copy to a package of its own keeps the two apart, so
# OMEMO runs on the protobuf it was written for. The new name is as long as the old one: OsmAnd's protobuf keeps
# its own descriptor as length-prefixed bytes in a string, and that string names the package.
PROTOBUF_OLD=com.google.protobuf
PROTOBUF_NEW=net.osmand.protobuf
[ ${#PROTOBUF_OLD} -eq ${#PROTOBUF_NEW} ]
mkdir -p osmand/android/OsmAnd-java/src/main/java/net/osmand/protobuf
mv osmand/android/OsmAnd-java/src/main/java/com/google/protobuf/* osmand/android/OsmAnd-java/src/main/java/net/osmand/protobuf/
rmdir osmand/android/OsmAnd-java/src/main/java/com/google/protobuf
grep -rlF "$PROTOBUF_OLD" osmand/android/OsmAnd-java osmand/android/OsmAnd-shared --include='*.java' --include='*.kt' \
  | xargs -r sed -i "s/com\.google\.protobuf/net.osmand.protobuf/g"
echo "Files still naming $PROTOBUF_OLD: $(grep -rlF "$PROTOBUF_OLD" osmand/android/OsmAnd-java osmand/android/OsmAnd-shared --include='*.java' --include='*.kt' | wc -l)"
# OsmAnd's XML parser library also carries the XmlPull interfaces Android already has, and R8 refuses an app
# that redefines them. The app takes this copy without them.
mkdir -p osmand/libs
# Maven Central kept answering 404 to the runners for this file, so a copy is kept in the repository (vendor/,
# BSD licensed, 43 KB) and checked against Maven Central's SHA-1, ccbc77a5fd907ef863c29f3596c6f54ffa4e9442.
cp .github/scripts/vendor/kxml2-2.3.0.jar osmand/libs/kxml2-2.3.0.jar
echo "f264dd9f79a1fde10ce5ecc53221eff24be4c9331c830b7d52f2f08a7b633de2  osmand/libs/kxml2-2.3.0.jar" | sha256sum -c -
zip -q -d osmand/libs/kxml2-2.3.0.jar 'org/xmlpull/*'
unzip -l osmand/libs/kxml2-2.3.0.jar | tail -1
# The names of OsmAnd's place types (restaurant, fuel, ...) in English and Arabic, so the offline search finds a
# category by its Arabic name. They come from OsmAnd's app strings, which the core library does not carry.
PHRASES=TMessagesProj/src/osmand/assets/osmand_phrases
mkdir -p "$PHRASES"
cp osmand/android/OsmAnd/res/values/phrases.xml "$PHRASES/en.xml"
cp osmand/android/OsmAnd/res/values-ar/phrases.xml "$PHRASES/ar.xml"
ls -l "$PHRASES"
