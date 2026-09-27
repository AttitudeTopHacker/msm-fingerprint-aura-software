#!/bin/bash
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
# Script to build all release artifacts (.deb, .zip, .tar.gz, .apk)

set -e

VERSION="1.0.0"
DIST_DIR="dist"

echo "=================================================="
echo "  🚀 Building AuraLock Complete Release Bundle (v${VERSION})"
echo "  Organization: msm.movin"
echo "=================================================="

mkdir -p "$DIST_DIR"

# 1. Build .deb package
chmod +x scripts/build-deb.sh
./scripts/build-deb.sh

# 2. Package Android APK
echo "📱 Copying Android APK..."
if [ -f "mobile/android-app/app/build/outputs/apk/debug/app-debug.apk" ]; then
    cp "mobile/android-app/app/build/outputs/apk/debug/app-debug.apk" "$DIST_DIR/auralock-android-v${VERSION}.apk"
    echo "  ✅ Copied APK to $DIST_DIR/auralock-android-v${VERSION}.apk"
fi

# 3. Create Universal Linux Release ZIP & TAR.GZ
echo "📦 Packaging Linux universal archive..."
BUNDLE_DIR="dist/auralock-v${VERSION}-linux-x86_64"
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR"

cp target/release/auralock "$BUNDLE_DIR/"
cp target/release/libpam_auralock.so "$BUNDLE_DIR/"
cp scripts/install-pam.sh "$BUNDLE_DIR/"
cp scripts/auralock.service "$BUNDLE_DIR/"
cp README.md "$BUNDLE_DIR/"
if [ -f "$DIST_DIR/auralock-android-v${VERSION}.apk" ]; then
    cp "$DIST_DIR/auralock-android-v${VERSION}.apk" "$BUNDLE_DIR/"
fi

# Create .zip archive
cd dist
zip -r "auralock-v${VERSION}-linux-x86_64.zip" "auralock-v${VERSION}-linux-x86_64"
tar -czvf "auralock-v${VERSION}-linux-x86_64.tar.gz" "auralock-v${VERSION}-linux-x86_64"
cd ..

echo ""
echo "=================================================="
echo "  🎉 All Release Packages Built Successfully!"
echo "=================================================="
echo "  📁 Debian / Ubuntu Package: dist/auralock_${VERSION}_amd64.deb"
echo "  📁 Universal ZIP Bundle:    dist/auralock-v${VERSION}-linux-x86_64.zip"
echo "  📁 Linux Tarball:           dist/auralock-v${VERSION}-linux-x86_64.tar.gz"
echo "  📱 Android APK:             dist/auralock-android-v${VERSION}.apk"
echo "=================================================="
