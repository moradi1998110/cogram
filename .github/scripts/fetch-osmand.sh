#!/bin/bash
# Fetches OsmAnd's map engine next to Phogram for the Maps tab (phase 0), laid out the way OsmAnd's
# scripts expect: the app repository with its resources and native core beside it. settings.gradle adds
# OsmAnd's modules to the build when osmand/android/OsmAnd exists.
set -e
OSMAND_COMMIT=b8136d9c410350ccf6e31ff027f52bae9bd9a9cb
mkdir -p osmand && cd osmand
git init -q android
git -C android fetch -q --depth 1 https://github.com/osmandapp/OsmAnd.git "$OSMAND_COMMIT"
git -C android checkout -q FETCH_HEAD
for repo in OsmAnd-resources:resources OsmAnd-core-legacy:core-legacy OsmAnd-core:core OsmAnd-build:build; do
  git clone -q --depth 1 "https://github.com/osmandapp/${repo%%:*}.git" "${repo##*:}"
done
for d in android resources core-legacy core build; do
  echo "$d $(git -C $d rev-parse HEAD)"
done
cd ..
# OsmAnd's build scripts read their SDK versions from the root project.
cp osmand/android/versions.gradle versions.gradle
# OsmAnd's native build script finds the toolchain through these.
{
  echo "ANDROID_SDK_ROOT=$ANDROID_HOME"
  echo "ANDROID_NDK=$ANDROID_HOME/ndk/$NDK_VERSION"
  echo "ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/$NDK_VERSION"
} >> "$GITHUB_ENV"
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
grep -rlF "$PROTOBUF_OLD" osmand/android --include='*.java' --include='*.kt' --include='*.pro' \
  | xargs sed -i "s/com\.google\.protobuf/net.osmand.protobuf/g"
echo "Files still naming $PROTOBUF_OLD: $(grep -rlF "$PROTOBUF_OLD" osmand/android --include='*.java' --include='*.kt' --include='*.pro' | wc -l)"
# Phogram does not ship OsmAnd's 72 MB world map; the Maps tab offers it for download from OsmAnd's servers. Without
# this entry OsmAnd does not look for the map among the app's files at start.
python3 - <<'EOF'
import json
path = 'osmand/resources/bundled_assets.json'
with open(path) as f:
    data = json.load(f)
before = len(data['assets'])
data['assets'] = [a for a in data['assets'] if a.get('source') != 'World_basemap_mini.obf']
with open(path, 'w') as f:
    json.dump(data, f, indent=2)
print('Bundled assets: %d -> %d' % (before, len(data['assets'])))
EOF
# OsmAnd's map downloads run in its foreground service so they go on when the app is in the background. OsmAnd's
# library manifest leaves the service to OsmAnd's app manifest, so it is added here, not exported. The download
# notification opens OsmAnd's download screen, which Phogram does not have; it opens Phogram instead.
python3 - <<'EOF'
path = 'osmand/android/OsmAnd/AndroidManifest-library.xml'
with open(path) as f:
    text = f.read()
service = '''
	<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
	<application>
		<service
			android:name="net.osmand.plus.download.DownloadService"
			android:stopWithTask="false"
			android:exported="false"
			android:foregroundServiceType="dataSync" />
	</application>
</manifest>'''
assert text.count('</manifest>') == 1
with open(path, 'w') as f:
    f.write(text.replace('</manifest>', service))
print('Download service added to the library manifest')

path = 'osmand/android/OsmAnd/src/net/osmand/plus/notifications/DownloadNotification.java'
with open(path) as f:
    text = f.read()
old = 'Intent intent = new Intent(app, DownloadActivity.class);'
assert text.count(old) == 1
text = text.replace(old, 'Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());')
with open(path, 'w') as f:
    f.write(text)
print('Download notification opens the app')
EOF
# OsmAnd's XML parser library also carries the XmlPull interfaces Android already has, and R8 refuses an app
# that redefines them. The app takes this copy without them.
mkdir -p osmand/libs
curl -sSfL --retry 5 -o osmand/libs/kxml2-2.3.0.jar https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar
zip -q -d osmand/libs/kxml2-2.3.0.jar 'org/xmlpull/*'
unzip -l osmand/libs/kxml2-2.3.0.jar | tail -1
