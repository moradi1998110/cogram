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
