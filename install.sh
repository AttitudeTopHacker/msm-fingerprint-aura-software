#!/usr/bin/env bash
#
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
#
# AuraLock 1-Click Installer, Updater & Launcher
#

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

TARGET_VER="1.6.2"
if [ -f "$PROJECT_ROOT/VERSION" ]; then
    TARGET_VER=$(cat "$PROJECT_ROOT/VERSION" | tr -d '[:space:]')
fi

echo "════════════════════════════════════════════════════════════"
echo "  🌟 AuraLock Setup & Launcher v$TARGET_VER (msm.movin)"
echo "════════════════════════════════════════════════════════════"

# Detect if an existing installation exists on the system
IS_INSTALLED=false
INSTALLED_VER="Unknown"

if [ -f "/usr/local/bin/auralock" ]; then
    IS_INSTALLED=true
    INSTALLED_VER=$(/usr/local/bin/auralock --version 2>/dev/null | awk '{print $2}' || echo "Installed")
elif [ -f "/usr/bin/auralock" ]; then
    IS_INSTALLED=true
    INSTALLED_VER=$(/usr/bin/auralock --version 2>/dev/null | awk '{print $2}' || echo "Installed")
elif [ -f "/usr/bin/auralock-cli" ]; then
    IS_INSTALLED=true
    INSTALLED_VER=$(/usr/bin/auralock-cli --version 2>/dev/null | awk '{print $2}' || echo "Installed")
elif dpkg -l 2>/dev/null | grep -q "^ii[[:space:]]*auralock"; then
    IS_INSTALLED=true
    INSTALLED_VER=$(dpkg -l auralock 2>/dev/null | grep "^ii" | awk '{print $3}' || echo "Installed")
elif [ -f "/lib/security/pam_auralock.so" ] || [ -f "/usr/lib/security/pam_auralock.so" ]; then
    IS_INSTALLED=true
fi

MODE="fresh"

# Parse CLI flags if passed
for arg in "$@"; do
    case "$arg" in
        --update|-u)
            MODE="update"
            ;;
        --reinstall|-r)
            MODE="reinstall"
            ;;
        --fresh|-f)
            MODE="fresh"
            ;;
    esac
done

if [ "$IS_INSTALLED" = true ] && [ "$MODE" = "fresh" ]; then
    echo "  ⚠️  Previous AuraLock Installation Detected!"
    echo "  Installed Version : v$INSTALLED_VER"
    echo "  Target Version    : v$TARGET_VER"
    echo "────────────────────────────────────────────────────────────"
    echo "  What would you like to do?"
    echo "  [1] Update / Upgrade to v$TARGET_VER (Recommended)"
    echo "      - Preserves paired phone data & configuration"
    echo "      - Updates binaries, PAM module & restarts service"
    echo ""
    echo "  [2] Clean Reinstall"
    echo "      - Completely wipes old installation & services"
    echo "      - Performs a 100% fresh, clean installation"
    echo ""
    echo "  [3] Cancel"
    echo "────────────────────────────────────────────────────────────"

    if [ -t 0 ]; then
        read -p "Select option [1-3] (Default: 1): " USER_CHOICE
        case "$USER_CHOICE" in
            2)
                MODE="reinstall"
                ;;
            3)
                echo "❌ Installation cancelled."
                exit 0
                ;;
            *)
                MODE="update"
                ;;
        esac
    else
        # Non-interactive terminal: default to update
        echo "ℹ️  Non-interactive terminal detected. Defaulting to Update / Upgrade..."
        MODE="update"
    fi
fi

if [ "$MODE" = "reinstall" ]; then
    echo "🛑 Running clean uninstaller..."
    if [ -f "./uninstall.sh" ]; then
        ./uninstall.sh
    fi
    echo "✨ Old installation wiped cleanly. Proceeding with fresh install..."
elif [ "$MODE" = "update" ]; then
    echo "🔄 Preparing update: stopping running service temporarily..."
    sudo systemctl stop auralock.service 2>/dev/null || true
fi

echo "🔨 1/4. Building release binaries (auralock + PAM module)..."
cargo build --release --package auralock-cli --package auralock-pam

# Copy CLI binary
echo "📦 2/4. Installing CLI binary to /usr/local/bin/auralock..."
sudo cp target/release/auralock /usr/local/bin/auralock
sudo chmod 755 /usr/local/bin/auralock

# Also update /usr/bin/auralock-cli if present or used by systemd
sudo cp target/release/auralock /usr/bin/auralock-cli 2>/dev/null || true
sudo chmod 755 /usr/bin/auralock-cli 2>/dev/null || true

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
    if grep -q "@include common-auth" /etc/pam.d/sudo; then
        # Sudo already inherits common-auth; remove any duplicate direct pam_auralock entry
        if grep -q "pam_auralock.so" /etc/pam.d/sudo; then
            echo "🔧 Cleaning redundant pam_auralock entry in /etc/pam.d/sudo (inherited via common-auth)..."
            sudo sed -i '/pam_auralock.so/d' /etc/pam.d/sudo || true
        fi
        echo "   ✅ Sudo biometric enabled cleanly via common-auth."
    elif ! grep -q "pam_auralock.so" /etc/pam.d/sudo; then
        echo "🔒 5/5. Configuring /etc/pam.d/sudo for instant biometric sudo..."
        sudo sed -i '1s/^/auth sufficient pam_auralock.so\n/' /etc/pam.d/sudo
        echo "   ✅ Sudo biometric enabled safely."
    fi
fi

# Ensure systemd service is updated and active
if [ -f "scripts/auralock.service" ]; then
    sudo cp scripts/auralock.service /etc/systemd/system/auralock.service 2>/dev/null || true
    sudo systemctl daemon-reload 2>/dev/null || true
    sudo systemctl enable auralock.service 2>/dev/null || true
    sudo systemctl restart auralock.service 2>/dev/null || true
fi

echo ""
echo "════════════════════════════════════════════════════════════"
if [ "$MODE" = "update" ]; then
    echo "  🎉 AuraLock v$TARGET_VER Update Complete!"
    echo "  Background service active with socket permissions 0666."
else
    echo "  🎉 AuraLock v$TARGET_VER Installation Complete!"
fi
echo "  Launch the Control Center GUI with: auralock"
echo "════════════════════════════════════════════════════════════"
echo ""

# Launch AuraLock Daemon & Control Center UI if run directly in desktop session
if [ -n "$DISPLAY" ] || [ -n "$WAYLAND_DISPLAY" ]; then
    exec /usr/local/bin/auralock
fi
