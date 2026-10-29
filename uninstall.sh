#!/usr/bin/env bash
#
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
#
# AuraLock Clean Uninstaller
#

set -e

echo "════════════════════════════════════════════════════════════"
echo "  🛑 AuraLock Clean Uninstaller (msm.movin)"
echo "════════════════════════════════════════════════════════════"

echo "🛑 1/4. Stopping and disabling systemd service..."
sudo systemctl stop auralock.service 2>/dev/null || true
sudo systemctl disable auralock.service 2>/dev/null || true
sudo rm -f /etc/systemd/system/auralock.service /lib/systemd/system/auralock.service
sudo systemctl daemon-reload || true

echo "🧹 2/4. Removing Debian package (if installed)..."
if dpkg -l 2>/dev/null | grep -q "[a]uralock"; then
    sudo dpkg -P auralock 2>/dev/null || sudo apt remove --purge -y auralock 2>/dev/null || true
fi

echo "🔒 3/4. Cleaning PAM security configurations..."
if [ -f "/etc/pam.d/common-auth" ]; then
    sudo sed -i '/pam_auralock.so/d' /etc/pam.d/common-auth || true
fi
if [ -f "/etc/pam.d/sudo" ]; then
    sudo sed -i '/pam_auralock.so/d' /etc/pam.d/sudo || true
fi

echo "🗑️ 4/4. Removing binary files, PAM modules, sockets, and desktop entries..."
sudo rm -f /usr/local/bin/auralock
sudo rm -f /usr/bin/auralock
sudo rm -f /usr/bin/auralock-cli
sudo rm -f /usr/bin/auralock-desktop
sudo rm -f /lib/security/pam_auralock.so
sudo rm -f /usr/lib/security/pam_auralock.so
sudo rm -f /tmp/auralock_daemon.sock
sudo rm -f /tmp/msmconnect_auth_token

# Remove Desktop entries & launcher icons
sudo rm -f /usr/share/applications/auralock.desktop
sudo rm -f /usr/share/applications/msmconnect.desktop
rm -f "$HOME/.local/share/applications/auralock.desktop" 2>/dev/null || true
rm -f "$HOME/.local/share/applications/msmconnect.desktop" 2>/dev/null || true
sudo rm -f /usr/share/pixmaps/auralock.png /usr/share/pixmaps/msmconnect.png
sudo rm -f /usr/share/icons/hicolor/*/apps/auralock.png /usr/share/icons/hicolor/*/apps/msmconnect.png
rm -f "$HOME/.local/share/icons/msmconnect.png" 2>/dev/null || true

# Refresh desktop launcher cache
if command -v update-desktop-database >/dev/null 2>&1; then
    sudo update-desktop-database -q /usr/share/applications 2>/dev/null || true
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    sudo gtk-update-icon-cache -q /usr/share/icons/hicolor 2>/dev/null || true
fi

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  ✅ AuraLock has been completely and cleanly uninstalled!"
echo "════════════════════════════════════════════════════════════"
