#!/bin/bash
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
# Script to build .deb Debian/Ubuntu installer package for AuraLock

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION=$(tr -d '\r\n' < "$ROOT_DIR/VERSION" 2>/dev/null || echo "1.3.3")
PACKAGE_NAME="auralock"
ARCH="amd64"
BUILD_DIR="$ROOT_DIR/dist/deb_pkg"
DEB_NAME="${PACKAGE_NAME}_${VERSION}_${ARCH}.deb"

echo "=================================================="
echo "  📦 Building AuraLock .deb Installer (v${VERSION})"
echo "  Organization: msm.movin"
echo "=================================================="

# Ensure release binaries are built
echo "🔨 Building Rust release binaries (Desktop Native App + CLI + PAM)..."
cargo build --release --package auralock-desktop --package auralock-cli --package auralock-pam

# Clean & create packaging directories
rm -rf "$BUILD_DIR" dist/"$DEB_NAME"
mkdir -p "$BUILD_DIR/DEBIAN"
mkdir -p "$BUILD_DIR/usr/bin"
mkdir -p "$BUILD_DIR/usr/local/bin"
mkdir -p "$BUILD_DIR/lib/security"
mkdir -p "$BUILD_DIR/etc/systemd/system"
mkdir -p "$BUILD_DIR/usr/share/applications"
mkdir -p "$BUILD_DIR/usr/share/pixmaps"
mkdir -p "$BUILD_DIR/usr/share/icons/hicolor/128x128/apps"

# Copy binaries (Native GUI as primary, CLI for background/daemon)
if [ -f "target/release/auralock-desktop" ]; then
    cp target/release/auralock-desktop "$BUILD_DIR/usr/bin/auralock"
    cp target/release/auralock-desktop "$BUILD_DIR/usr/bin/auralock-desktop"
    cp target/release/auralock-desktop "$BUILD_DIR/usr/local/bin/auralock"
    chmod 755 "$BUILD_DIR/usr/bin/auralock" "$BUILD_DIR/usr/bin/auralock-desktop" "$BUILD_DIR/usr/local/bin/auralock"
fi

if [ -f "target/release/auralock" ]; then
    cp target/release/auralock "$BUILD_DIR/usr/bin/auralock-cli"
    cp target/release/auralock "$BUILD_DIR/usr/local/bin/auralock-cli"
    chmod 755 "$BUILD_DIR/usr/bin/auralock-cli" "$BUILD_DIR/usr/local/bin/auralock-cli"
fi

cp target/release/libpam_auralock.so "$BUILD_DIR/lib/security/pam_auralock.so"
chmod 644 "$BUILD_DIR/lib/security/pam_auralock.so"

cp scripts/auralock.service "$BUILD_DIR/etc/systemd/system/auralock.service"
chmod 644 "$BUILD_DIR/etc/systemd/system/auralock.service"

# Copy App Icons
if [ -f "src-tauri/icons/icon.png" ]; then
    cp "src-tauri/icons/icon.png" "$BUILD_DIR/usr/share/pixmaps/auralock.png"
fi
if [ -f "src-tauri/icons/128x128.png" ]; then
    cp "src-tauri/icons/128x128.png" "$BUILD_DIR/usr/share/icons/hicolor/128x128/apps/auralock.png"
fi

# Create Desktop Entry (Native Standalone Desktop Application)
cat << 'EOF' > "$BUILD_DIR/usr/share/applications/auralock.desktop"
[Desktop Entry]
Name=AuraLock
GenericName=Biometric Security & AuraDrop
Comment=Instant Remote Biometric Unlock & AuraDrop for Linux
Exec=auralock
Icon=auralock
Terminal=false
Type=Application
Categories=Utility;Security;System;
StartupNotify=true
Keywords=fingerprint;biometric;security;lock;unlock;auradrop;
EOF
chmod 644 "$BUILD_DIR/usr/share/applications/auralock.desktop"

# Create DEBIAN/control
cat << EOF > "$BUILD_DIR/DEBIAN/control"
Package: auralock
Version: ${VERSION}
Section: utils
Priority: optional
Architecture: ${ARCH}
Maintainer: msm.movin <support@msm.movin>
Depends: libpam0g, systemd
Description: AuraLock - Instant Remote Biometric Unlock for Linux
 AuraLock turns your Android smartphone's fingerprint sensor into a hardware
 biometric key for Linux lockscreens and sudo authentication over local Wi-Fi and BLE.
 Developed by msm.movin. Copyright (c) 2022 msm.movin. All rights reserved.
EOF

# Create DEBIAN/postinst (Runs after installation)
cat << 'EOF' > "$BUILD_DIR/DEBIAN/postinst"
#!/bin/sh
set -e

if [ "$1" = "configure" ]; then
    echo "⚙️ Reloading systemd daemon..."
    systemctl daemon-reload || true
    echo "🚀 Enabling and starting auralock background service..."
    systemctl enable auralock.service || true
    systemctl restart auralock.service || true

    if command -v update-desktop-database > /dev/null 2>&1; then
        update-desktop-database -q /usr/share/applications || true
    fi
    if command -v gtk-update-icon-cache > /dev/null 2>&1; then
        gtk-update-icon-cache -q /usr/share/icons/hicolor || true
    fi

    echo ""
    echo "=================================================="
    echo "  🎉 AuraLock installed successfully!"
    echo "  Open 'AuraLock' from your Application Menu or run 'auralock gui'."
    echo "=================================================="
fi
exit 0
EOF
chmod 755 "$BUILD_DIR/DEBIAN/postinst"

# Create DEBIAN/prerm (Runs before removal)
cat << 'EOF' > "$BUILD_DIR/DEBIAN/prerm"
#!/bin/sh
set -e

if [ "$1" = "remove" ] || [ "$1" = "purge" ]; then
    echo "🛑 Stopping and disabling auralock service..."
    systemctl stop auralock.service || true
    systemctl disable auralock.service || true

    if command -v update-desktop-database > /dev/null 2>&1; then
        update-desktop-database -q /usr/share/applications || true
    fi
fi
exit 0
EOF
chmod 755 "$BUILD_DIR/DEBIAN/prerm"

# Build the .deb package
echo "📦 Packaging with dpkg-deb..."
dpkg-deb --build --root-owner-group "$BUILD_DIR" "dist/$DEB_NAME"

echo ""
echo "✅ .deb package created successfully at: dist/$DEB_NAME"
echo "👉 Install with: sudo dpkg -i dist/$DEB_NAME"
