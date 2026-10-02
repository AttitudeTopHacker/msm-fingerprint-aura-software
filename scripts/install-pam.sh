#!/usr/bin/env bash
#
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
#

set -e

echo "════════════════════════════════════════════════════"
echo "  🌟 AuraLock Linux PAM Module Installer (msm.movin)"
echo "════════════════════════════════════════════════════"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SO_SOURCE="$ROOT_DIR/target/release/libpam_auralock.so"

if [ ! -f "$SO_SOURCE" ]; then
    echo "🔨 Building PAM module in release mode..."
    cargo build --release --package auralock-pam
fi

DEST_DIR="/lib/security"
if [ ! -d "$DEST_DIR" ]; then
    DEST_DIR="/usr/lib/security"
fi

echo "📦 Installing PAM module to $DEST_DIR/pam_auralock.so..."
sudo cp "$SO_SOURCE" "$DEST_DIR/pam_auralock.so"
sudo chmod 755 "$DEST_DIR/pam_auralock.so"
sudo chown root:root "$DEST_DIR/pam_auralock.so"

# Install pam-config profile if directory exists (Ubuntu / Debian / Mint)
if [ -d "/usr/share/pam-configs" ]; then
    echo "⚙️ Registering with PAM auth update (/usr/share/pam-configs/auralock)..."
    sudo cp "$SCRIPT_DIR/pam-config-auralock" "/usr/share/pam-configs/auralock"
    sudo chmod 644 "/usr/share/pam-configs/auralock"
    sudo pam-auth-update --package || true
fi

echo ""
echo "=================================================="
echo "  🎉 AuraLock Lock Screen & Sudo PAM is now ACTIVE!"
echo "  When you lock your screen (Super + L) or use sudo,"
echo "  AuraLock will prompt on screen & phone."
echo "=================================================="
