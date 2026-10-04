#!/usr/bin/env bash
#
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
#
# AuraLock 1-Click Installer & Launcher
#

set -e

echo "════════════════════════════════════════════════════════════"
echo "  🌟 AuraLock 1-Click Auto Installer & Launcher (msm.movin)"
echo "════════════════════════════════════════════════════════════"

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

echo "🔨 1/4. Building release binaries (auralock + PAM module)..."
cargo build --release --package auralock-cli --package auralock-pam

# Copy CLI binary
echo "📦 2/4. Installing CLI binary to /usr/local/bin/auralock..."
sudo cp target/release/auralock /usr/local/bin/auralock
sudo chmod 755 /usr/local/bin/auralock

# Copy PAM module
PAM_DEST="/lib/security"
if [ ! -d "$PAM_DEST" ]; then
    PAM_DEST="/usr/lib/security"
fi

echo "📦 3/4. Installing Linux PAM module to $PAM_DEST/pam_auralock.so..."
sudo cp target/release/libpam_auralock.so "$PAM_DEST/pam_auralock.so"
sudo chmod 644 "$PAM_DEST/pam_auralock.so"
sudo chown root:root "$PAM_DEST/pam_auralock.so"

# Safely add PAM entry to common-auth and sudo if not already present
if [ -f "/etc/pam.d/common-auth" ]; then
    if ! grep -q "pam_auralock.so" /etc/pam.d/common-auth; then
        echo "🔒 4/5. Configuring /etc/pam.d/common-auth for lockscreen biometric..."
        sudo sed -i '1s/^/auth sufficient pam_auralock.so\n/' /etc/pam.d/common-auth
        echo "   ✅ Lockscreen biometric enabled safely."
    else
        echo "   ℹ️ /etc/pam.d/common-auth already configured."
    fi
fi

if [ -f "/etc/pam.d/sudo" ]; then
    if ! grep -q "pam_auralock.so" /etc/pam.d/sudo; then
        echo "🔒 5/5. Configuring /etc/pam.d/sudo for instant biometric sudo..."
        sudo sed -i '1s/^/auth sufficient pam_auralock.so\n/' /etc/pam.d/sudo
        echo "   ✅ Sudo biometric enabled safely."
    else
        echo "   ℹ️ /etc/pam.d/sudo already configured."
    fi
fi

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  🎉 Installation Complete! Launching AuraLock Daemon..."
echo "════════════════════════════════════════════════════════════"
echo ""

# Launch AuraLock Daemon & Control Center UI
exec /usr/local/bin/auralock daemon
