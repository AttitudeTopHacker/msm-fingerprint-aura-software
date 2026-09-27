#!/usr/bin/env bash
#
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
#

set -e

echo "════════════════════════════════════════════════════"
echo "  🌟 AuraLock Linux PAM Module Installer (msm.movin)"
echo "════════════════════════════════════════════════════"

SO_SOURCE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/target/release/libpam_auralock.so"

if [ ! -f "$SO_SOURCE" ]; then
    echo "❌ Error: libpam_auralock.so not found. Please run 'cargo build --release' first."
    exit 1
fi

DEST_DIR="/lib/security"
if [ ! -d "$DEST_DIR" ]; then
    DEST_DIR="/usr/lib/security"
fi

echo "📦 Installing PAM module to $DEST_DIR/pam_auralock.so..."
sudo cp "$SO_SOURCE" "$DEST_DIR/pam_auralock.so"
sudo chmod 755 "$DEST_DIR/pam_auralock.so"
sudo chown root:root "$DEST_DIR/pam_auralock.so"

echo "✅ Installed successfully!"
echo ""
echo "📝 To enable AuraLock on your lockscreen (e.g. GDM / GNOME / KDE / SDDM):"
echo "Add the following line to the TOP of /etc/pam.d/common-auth (or /etc/pam.d/gdm-password):"
echo ""
echo "   auth sufficient pam_auralock.so"
echo ""
echo "Note: 'sufficient' means phone biometric will unlock immediately if approved,"
echo "      but standard password fallback will continue to work if phone is offline."
