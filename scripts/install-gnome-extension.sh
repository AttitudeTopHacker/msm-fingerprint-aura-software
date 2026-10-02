#!/usr/bin/env bash
# Copyright (c) 2022 msm.movin. All rights reserved.
# Script to install AuraLock GNOME Shell Lock Screen Extension

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
EXT_DIR="$ROOT_DIR/gnome-extension/auralock@msm.movin"

UUID="auralock@msm.movin"
USER_EXT_DIR="$HOME/.local/share/gnome-shell/extensions/$UUID"
SYS_EXT_DIR="/usr/share/gnome-shell/extensions/$UUID"

echo "=================================================="
echo "  🌟 Installing AuraLock Lock Screen Extension"
echo "=================================================="

# 1. Install to User directory
mkdir -p "$USER_EXT_DIR"
cp -r "$EXT_DIR"/* "$USER_EXT_DIR/"
echo "✅ Installed to user directory: $USER_EXT_DIR"

# 2. Try enabling via gnome-extensions if command exists
if command -v gnome-extensions > /dev/null 2>&1; then
    gnome-extensions enable "$UUID" 2>/dev/null || true
    echo "✅ Enabled extension: $UUID"
fi

# 3. If root / sudo, install system-wide
if [ "$EUID" -eq 0 ]; then
    mkdir -p "$SYS_EXT_DIR"
    cp -r "$EXT_DIR"/* "$SYS_EXT_DIR/"
    echo "✅ Installed system-wide to: $SYS_EXT_DIR"
fi

echo ""
echo "=================================================="
echo "  🎉 AuraLock Lock Screen UI Installed Successfully!"
echo "=================================================="
