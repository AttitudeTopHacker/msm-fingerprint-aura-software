#!/bin/bash
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
# Script to build .deb Debian/Ubuntu installer package for AuraLock

set -e

VERSION="1.0.0"
PACKAGE_NAME="auralock"
ARCH="amd64"
BUILD_DIR="dist/deb_pkg"
DEB_NAME="${PACKAGE_NAME}_${VERSION}_${ARCH}.deb"

echo "=================================================="
echo "  📦 Building AuraLock .deb Installer (v${VERSION})"
echo "  Organization: msm.movin"
echo "=================================================="

# Ensure release binaries are built
echo "🔨 Building Rust release binaries..."
cargo build --release

# Clean & create packaging directories
rm -rf "$BUILD_DIR" dist/"$DEB_NAME"
mkdir -p "$BUILD_DIR/DEBIAN"
mkdir -p "$BUILD_DIR/usr/local/bin"
mkdir -p "$BUILD_DIR/lib/security"
mkdir -p "$BUILD_DIR/etc/systemd/system"

# Copy binaries and service file
cp target/release/auralock "$BUILD_DIR/usr/local/bin/auralock"
chmod 755 "$BUILD_DIR/usr/local/bin/auralock"

cp target/release/libpam_auralock.so "$BUILD_DIR/lib/security/pam_auralock.so"
chmod 644 "$BUILD_DIR/lib/security/pam_auralock.so"

cp scripts/auralock.service "$BUILD_DIR/etc/systemd/system/auralock.service"
chmod 644 "$BUILD_DIR/etc/systemd/system/auralock.service"

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
    echo ""
    echo "=================================================="
    echo "  🎉 AuraLock installed successfully!"
    echo "  Run 'auralock pair' to pair with your Android phone."
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
