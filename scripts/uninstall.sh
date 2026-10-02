#!/usr/bin/env bash
# Copyright (c) 2022 msm.movin. All rights reserved.
# Script to completely and cleanly uninstall/purge AuraLock from the system.

set -e

echo "=================================================="
echo "  🗑️  Completely Uninstalling AuraLock (msm.movin)"
echo "=================================================="

# 1. Stop and disable background service
echo "🛑 Stopping background services & processes..."
sudo systemctl stop auralock.service 2>/dev/null || true
sudo systemctl disable auralock.service 2>/dev/null || true
sudo systemctl --user stop auralock.service 2>/dev/null || true
sudo systemctl --user disable auralock.service 2>/dev/null || true
sudo pkill -9 -f auralock 2>/dev/null || true

# 2. Remove dpkg package if installed
if dpkg -l | grep -q auralock; then
    echo "📦 Purging dpkg package 'auralock'..."
    sudo dpkg -P auralock 2>/dev/null || sudo dpkg -r auralock 2>/dev/null || true
fi

# 3. Clean PAM configurations cleanly
echo "🔐 Cleaning PAM configs..."
sudo rm -f /usr/share/pam-configs/auralock /usr/share/pam-configs/pam_auralock
if command -v pam-auth-update > /dev/null 2>&1; then
    sudo pam-auth-update --package 2>/dev/null || true
fi
# Extra safety: remove direct pam_auralock lines if any remain
if [ -f /etc/pam.d/common-auth ]; then
    sudo sed -i '/pam_auralock.so/d' /etc/pam.d/common-auth 2>/dev/null || true
fi

# 4. Remove all system binaries, libraries, and services
echo "🧹 Removing system files, binaries & libraries..."
sudo rm -f /usr/bin/auralock /usr/bin/auralock-cli /usr/bin/auralock-desktop /usr/bin/auralock*
sudo rm -f /usr/local/bin/auralock /usr/local/bin/auralock-cli /usr/local/bin/auralock-desktop /usr/local/bin/auralock*
sudo rm -f /lib/security/pam_auralock.so /usr/lib/security/pam_auralock.so /usr/lib/x86_64-linux-gnu/security/pam_auralock.so
sudo rm -f /etc/systemd/system/auralock.service /etc/systemd/user/auralock.service
sudo rm -f /usr/share/applications/auralock.desktop ~/.local/share/applications/auralock.desktop
sudo rm -f /usr/share/pixmaps/auralock.png
sudo rm -f /usr/share/icons/hicolor/*/apps/auralock.png
sudo rm -rf /usr/share/gnome-shell/extensions/auralock@msm.movin
sudo rm -rf "$HOME/.local/share/gnome-shell/extensions/auralock@msm.movin"
sudo rm -f /tmp/auralock* /tmp/auralock_daemon.sock

# 5. Clean user configs and caches
echo "🧹 Removing user configs and cache..."
rm -rf "$HOME/.config/auralock" "$HOME/.config/.auralock_app_profile"
rm -rf "$HOME/.cache/auralock" "$HOME/.cache/.auralock_app_profile"
sudo rm -rf /etc/auralock /var/lib/auralock

# 6. Reload systemd & desktop databases
echo "⚙️ Reloading system databases..."
sudo systemctl daemon-reload 2>/dev/null || true
if command -v update-desktop-database > /dev/null 2>&1; then
    sudo update-desktop-database -q /usr/share/applications 2>/dev/null || true
    update-desktop-database -q "$HOME/.local/share/applications" 2>/dev/null || true
fi
if command -v gtk-update-icon-cache > /dev/null 2>&1; then
    sudo gtk-update-icon-cache -q /usr/share/icons/hicolor 2>/dev/null || true
fi

echo ""
echo "=================================================="
echo "  🎉 AuraLock has been completely and cleanly uninstalled!"
echo "  All old versions, services, and libraries have been removed."
echo "=================================================="

