#!/bin/bash
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
# Script to build .AppImage portable standalone Linux executable for AuraLock

set -e

VERSION="1.0.0"
PACKAGE_NAME="auralock"
ARCH="amd64"
DIST_DIR="dist"
APPIMAGE_NAME="${PACKAGE_NAME}_${VERSION}_${ARCH}.AppImage"
APP_DIR="dist/AppDir"

echo "=================================================="
echo "  📦 Building AuraLock AppImage (v${VERSION})"
echo "  Organization: msm.movin"
echo "=================================================="

# Ensure release binaries exist
cargo build --release

# Clean and setup AppDir
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR/usr/bin"
mkdir -p "$APP_DIR/usr/lib/security"
mkdir -p "$APP_DIR/usr/share/icons/hicolor/256x256/apps"

# Copy binary & PAM module
cp target/release/auralock "$APP_DIR/usr/bin/auralock"
chmod 755 "$APP_DIR/usr/bin/auralock"

cp target/release/libpam_auralock.so "$APP_DIR/usr/lib/security/pam_auralock.so"

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

# Create a clean default icon if not present
mkdir -p "$APP_DIR/usr/share/icons/hicolor/256x256/apps"
touch "$APP_DIR/auralock.png"
touch "$APP_DIR/.DirIcon"

# Build AppImage with appimagetool if available
if command -v appimagetool &> /dev/null; then
    echo "🔨 Running appimagetool..."
    ARCH=x86_64 appimagetool "$APP_DIR" "$DIST_DIR/$APPIMAGE_NAME"
    chmod +x "$DIST_DIR/$APPIMAGE_NAME"
    echo "✅ AppImage created successfully at: $DIST_DIR/$APPIMAGE_NAME"
else
    echo "⬇️ Downloading appimagetool..."
    wget -q https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage -O /tmp/appimagetool || curl -sL https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage -o /tmp/appimagetool
    if [ -f "/tmp/appimagetool" ]; then
        chmod +x /tmp/appimagetool
        ARCH=x86_64 /tmp/appimagetool --appimage-extract-and-run "$APP_DIR" "$DIST_DIR/$APPIMAGE_NAME" || ARCH=x86_64 /tmp/appimagetool "$APP_DIR" "$DIST_DIR/$APPIMAGE_NAME"
        chmod +x "$DIST_DIR/$APPIMAGE_NAME"
        echo "✅ AppImage created successfully at: $DIST_DIR/$APPIMAGE_NAME"
    else
        echo "⚠️ appimagetool could not be downloaded in this environment."
    fi
fi
