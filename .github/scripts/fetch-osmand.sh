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
# Maven Central now and then answers 404 to the runners for a file that exists, and curl does not retry a 404,
# so each mirror is tried a few times.
KXML=net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar
for attempt in 1 2 3 4; do
  for mirror in https://repo1.maven.org/maven2 https://repo.maven.apache.org/maven2; do
    curl -sSfL --retry 3 -o osmand/libs/kxml2-2.3.0.jar "$mirror/$KXML" && break 2
  done
  sleep 15
done
test -s osmand/libs/kxml2-2.3.0.jar
zip -q -d osmand/libs/kxml2-2.3.0.jar 'org/xmlpull/*'
unzip -l osmand/libs/kxml2-2.3.0.jar | tail -1
# Phogram ships no Google Play services, so OsmAnd goes without its Google Play parts too: location comes from
# Android's own location service, purchases from a stand-in that sells nothing and asks nobody
# (osmand-phogram/), and its "rate us" dialog never opens Google Play's review flow.
cp -r .github/scripts/osmand-phogram osmand/android/OsmAnd/src-phogram
python3 - <<'EOF'
import re
root = 'osmand/android/OsmAnd/'

def patch(path, old, new, count=1):
    with open(root + path) as f:
        text = f.read()
    assert text.count(old) == count, (path, old)
    with open(root + path, 'w') as f:
        f.write(text.replace(old, new))

# OsmAnd's library build takes Phogram's purchase stand-in in place of its Google Play sources.
patch('build-library.gradle', 'java.srcDirs = ["src", "src-gms", "src-google", "src-nogplay"]',
      'java.srcDirs = ["src", "src-phogram", "src-nogplay"]')
for dependency in ["implementation 'com.android.billingclient:billing:8.0.0'",
                   "implementation 'com.google.android.play:review:2.0.2'",
                   "implementation 'com.google.android.gms:play-services-location:21.3.0'"]:
    patch('build-common.gradle', dependency, '// Phogram: ' + dependency)

# The Google Play build's map activity hooks have nothing of Google in them, so they are kept.
import os, shutil
os.makedirs(root + 'src-phogram/net/osmand/plus/activities', exist_ok=True)
shutil.copy(root + 'src-google/net/osmand/plus/activities/ExtendedMapActivity.java',
            root + 'src-phogram/net/osmand/plus/activities/ExtendedMapActivity.java')

# The purchase list without the legacy subscription built from Google Play's product details.
with open(root + 'src-google/net/osmand/plus/inapp/InAppPurchasesImpl.java') as f:
    text = f.read()
text = text.replace('import com.android.billingclient.api.ProductDetails;\n', '')
text, n = re.subn(r'\n\tpublic static class InAppPurchaseLiveUpdatesOldSubscription extends InAppSubscription \{.*?\n\t\}\n',
                  '\n', text, count=1, flags=re.S)
assert n == 1 and 'ProductDetails' not in text
with open(root + 'src-phogram/net/osmand/plus/inapp/InAppPurchasesImpl.java', 'w') as f:
    f.write(text)

# Location always from Android's own service, never from Google Play services.
os.remove(root + 'src/net/osmand/plus/helpers/GmsLocationServiceHelper.java')
patch('src/net/osmand/plus/OsmandApplication.java',
      '\t\tif (source == LocationSource.GOOGLE_PLAY_SERVICES) {\n\t\t\treturn new GmsLocationServiceHelper(this);\n\t\t}\n', '')

# The "rate us" dialog is OsmAnd's own sheet, not Google Play's review flow.
path = 'src/net/osmand/plus/feedback/RateUsHelper.java'
with open(root + path) as f:
    text = f.read()
for line in ['import com.google.android.play.core.review.ReviewInfo;\n',
             'import com.google.android.play.core.review.ReviewManager;\n',
             'import com.google.android.play.core.review.ReviewManagerFactory;\n',
             'import com.google.android.gms.tasks.Task;\n']:
    assert text.count(line) == 1, line
    text = text.replace(line, '')
assert text.count('\t\t\tshowInAppRateDialog(mapActivity);\n') == 1
text = text.replace('\t\t\tshowInAppRateDialog(mapActivity);\n',
                    '\t\t\tRateUsBottomSheetDialogFragment.showInstance(mapActivity.getSupportFragmentManager());\n')
text, n = re.subn(r'\n\tprivate static void showInAppRateDialog\(FragmentActivity activity\) \{.*\n\}\s*$', '\n}\n', text,
                  count=1, flags=re.S)
assert n == 1 and 'ReviewManager' not in text
with open(root + path, 'w') as f:
    f.write(text)
print('OsmAnd goes without Google Play services, billing and review')
EOF
