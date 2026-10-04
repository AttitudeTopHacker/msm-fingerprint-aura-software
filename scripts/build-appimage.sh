#!/bin/bash
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
# Script to build .AppImage portable standalone Linux executable for AuraLock

set -e

VERSION=$(cat VERSION 2>/dev/null || echo "1.5.1")
PACKAGE_NAME="auralock"
ARCH="amd64"
PROJECT_ROOT="$(pwd)"
DIST_DIR="${PROJECT_ROOT}/dist"
APPIMAGE_NAME="${PACKAGE_NAME}_${VERSION}_${ARCH}.AppImage"
APP_DIR="${DIST_DIR}/AppDir"

echo "=================================================="
echo "  📦 Building AuraLock AppImage (v${VERSION})"
echo "  Organization: msm.movin"
echo "=================================================="

# Ensure release binaries exist
cargo build --release --package auralock-desktop --package auralock-cli --package auralock-pam

# Clean and setup AppDir
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR/usr/bin"
mkdir -p "$APP_DIR/usr/lib/security"
mkdir -p "$APP_DIR/usr/share/icons/hicolor/256x256/apps"

# Copy binary & PAM module
if [ -f "${PROJECT_ROOT}/target/release/auralock-desktop" ]; then
    cp "${PROJECT_ROOT}/target/release/auralock-desktop" "$APP_DIR/usr/bin/auralock"
else
    cp "${PROJECT_ROOT}/target/release/auralock" "$APP_DIR/usr/bin/auralock"
fi
chmod 755 "$APP_DIR/usr/bin/auralock"

if [ -f "${PROJECT_ROOT}/target/release/libpam_auralock.so" ]; then
    cp "${PROJECT_ROOT}/target/release/libpam_auralock.so" "$APP_DIR/usr/lib/security/pam_auralock.so"
fi

# Create Desktop Entry
cat << EOF > "$APP_DIR/auralock.desktop"
[Desktop Entry]
Name=AuraLock
Comment=Instant Remote Biometric Unlock for Linux
Exec=auralock daemon
Icon=auralock
Terminal=true
Type=Application
Categories=Utility;Security;
EOF

# Create AppRun entrypoint
cat << 'EOF' > "$APP_DIR/AppRun"
#!/bin/sh
SELF=$(readlink -f "$0")
HERE=${SELF%/*}
export PATH="${HERE}/usr/bin:${PATH}"
export LD_LIBRARY_PATH="${HERE}/usr/lib:${LD_LIBRARY_PATH}"

if [ $# -eq 0 ]; then
    exec "${HERE}/usr/bin/auralock" daemon
else
    exec "${HERE}/usr/bin/auralock" "$@"
fi
EOF
chmod 755 "$APP_DIR/AppRun"

# Create placeholder icon
touch "$APP_DIR/auralock.png"
touch "$APP_DIR/.DirIcon"

export ARCH=x86_64
export APPIMAGE_EXTRACT_AND_RUN=1

# Download or run appimagetool
if ! command -v appimagetool &> /dev/null; then
    wget -q https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage -O /tmp/appimagetool || curl -sL https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage -o /tmp/appimagetool
    chmod +x /tmp/appimagetool
    APPIMAGETOOL="/tmp/appimagetool"
else
    APPIMAGETOOL="appimagetool"
fi

echo "🔨 Running appimagetool..."
"$APPIMAGETOOL" --appimage-extract-and-run "$APP_DIR" "$DIST_DIR/$APPIMAGE_NAME" || "$APPIMAGETOOL" "$APP_DIR" "$DIST_DIR/$APPIMAGE_NAME" || true

if [ -f "$DIST_DIR/$APPIMAGE_NAME" ]; then
    chmod +x "$DIST_DIR/$APPIMAGE_NAME"
    echo "✅ AppImage created successfully at: $DIST_DIR/$APPIMAGE_NAME"
fi
