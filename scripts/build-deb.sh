#!/bin/bash
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
# Script to build .deb Debian/Ubuntu installer package for AuraLock

set -e

VERSION=$(cat VERSION 2>/dev/null || echo "1.5.1")
PACKAGE_NAME="auralock"
ARCH="amd64"
BUILD_DIR="dist/deb_pkg"
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
mkdir -p "$BUILD_DIR/usr/share/pixmaps"
mkdir -p "$BUILD_DIR/usr/share/icons/hicolor/128x128/apps"
mkdir -p "$BUILD_DIR/usr/share/icons/hicolor/512x512/apps"
mkdir -p "$BUILD_DIR/usr/share/applications"

if [ -f "src-tauri/icons/icon.png" ]; then
    cp "src-tauri/icons/icon.png" "$BUILD_DIR/usr/share/pixmaps/msmconnect.png"
    cp "src-tauri/icons/icon.png" "$BUILD_DIR/usr/share/pixmaps/auralock.png"
    cp "src-tauri/icons/icon.png" "$BUILD_DIR/usr/share/icons/hicolor/512x512/apps/msmconnect.png"
fi
if [ -f "src-tauri/icons/128x128.png" ]; then
    cp "src-tauri/icons/128x128.png" "$BUILD_DIR/usr/share/icons/hicolor/128x128/apps/msmconnect.png"
    cp "src-tauri/icons/128x128.png" "$BUILD_DIR/usr/share/icons/hicolor/128x128/apps/auralock.png"
fi

# Create Desktop Entry (Native Standalone Desktop Application)
cat << 'EOF' > "$BUILD_DIR/usr/share/applications/msmconnect.desktop"
[Desktop Entry]
Name=Msm Connect
GenericName=Biometric Security & File Transfer
Comment=Instant Remote Biometric Unlock & AuraDrop for Linux
Exec=auralock
Icon=msmconnect
Terminal=false
Type=Application
Categories=Utility;Security;System;
StartupNotify=true
Keywords=fingerprint;biometric;security;lock;unlock;msm;connect;auradrop;
EOF
chmod 644 "$BUILD_DIR/usr/share/applications/msmconnect.desktop"

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

    # Safely configure PAM for lock screen & sudo authentication
    if [ -f "/etc/pam.d/common-auth" ] && ! grep -q "pam_auralock.so" /etc/pam.d/common-auth; then
        sed -i '1s/^/auth sufficient pam_auralock.so\n/' /etc/pam.d/common-auth || true
    fi
    if [ -f "/etc/pam.d/sudo" ]; then
        if grep -q "@include common-auth" /etc/pam.d/sudo; then
            sed -i '/pam_auralock.so/d' /etc/pam.d/sudo || true
        elif ! grep -q "pam_auralock.so" /etc/pam.d/sudo; then
            sed -i '1s/^/auth sufficient pam_auralock.so\n/' /etc/pam.d/sudo || true
        fi
    fi

    # Clean up legacy desktop entry if present
    rm -f /usr/share/applications/auralock.desktop 2>/dev/null || true

    if command -v update-desktop-database > /dev/null 2>&1; then
        update-desktop-database -q /usr/share/applications || true
    fi
    if command -v gtk-update-icon-cache > /dev/null 2>&1; then
        gtk-update-icon-cache -q /usr/share/icons/hicolor || true
    fi

    echo ""
    echo "=================================================="
    echo "  🎉 Msm Connect installed successfully!"
    echo "  Open 'Msm Connect' from your Application Menu or run 'auralock'."
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

    # Remove PAM entries safely
    if [ -f "/etc/pam.d/common-auth" ]; then
        sed -i '/pam_auralock.so/d' /etc/pam.d/common-auth || true
    fi
    if [ -f "/etc/pam.d/sudo" ]; then
        sed -i '/pam_auralock.so/d' /etc/pam.d/sudo || true
    fi
fi
exit 0
EOF
chmod 755 "$BUILD_DIR/DEBIAN/prerm"

# Create DEBIAN/postrm (Runs after removal)
cat << 'EOF' > "$BUILD_DIR/DEBIAN/postrm"
#!/bin/sh
set -e

if [ "$1" = "remove" ] || [ "$1" = "purge" ]; then
    rm -f /usr/share/applications/auralock.desktop 2>/dev/null || true
    rm -f /usr/share/applications/msmconnect.desktop 2>/dev/null || true
    if command -v update-desktop-database > /dev/null 2>&1; then
        update-desktop-database -q /usr/share/applications || true
    fi
    if command -v gtk-update-icon-cache > /dev/null 2>&1; then
        gtk-update-icon-cache -q /usr/share/icons/hicolor || true
    fi
fi
exit 0
EOF
chmod 755 "$BUILD_DIR/DEBIAN/postrm"

# Build the .deb package with universal xz compression
echo "📦 Packaging with dpkg-deb (universal xz compression)..."
dpkg-deb -Zxz --build --root-owner-group "$BUILD_DIR" "dist/$DEB_NAME"

echo ""
echo "✅ .deb package created successfully at: dist/$DEB_NAME"
echo "👉 Install with: sudo dpkg -i dist/$DEB_NAME"
