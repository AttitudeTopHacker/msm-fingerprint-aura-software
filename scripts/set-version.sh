#!/bin/bash
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
# Script to update AuraLock version globally across the entire repository in 1 single command.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

NEW_VERSION="$1"

if [ -z "$NEW_VERSION" ]; then
    CURRENT_VERSION=$(tr -d '\r\n' < "$ROOT_DIR/VERSION" 2>/dev/null || echo "unknown")
    echo "=================================================="
    echo "  🏷️  AuraLock Version Manager (msm.movin)"
    echo "=================================================="
    echo "  Current Version: $CURRENT_VERSION"
    echo ""
    echo "  Usage: ./scripts/set-version.sh <new_version>"
    echo "  Example: ./scripts/set-version.sh 1.3.4"
    echo "  Or via npm: npm run bump 1.3.4"
    echo "=================================================="
    exit 1
fi

# Strip leading 'v' if provided by mistake (e.g. v1.3.4 -> 1.3.4)
NEW_VERSION="${NEW_VERSION#v}"

echo "=================================================="
echo "  🚀 Updating AuraLock Version to: v${NEW_VERSION}"
echo "=================================================="

# 1. Update VERSION file
echo "$NEW_VERSION" > "$ROOT_DIR/VERSION"
echo "  ✅ Updated: VERSION ($NEW_VERSION)"

# 2. Update Cargo.toml workspace root
if [ -f "$ROOT_DIR/Cargo.toml" ]; then
    sed -i -E 's/^version = "[0-9]+\.[0-9]+\.[0-9]+"/version = "'"$NEW_VERSION"'"/' "$ROOT_DIR/Cargo.toml"
    echo "  ✅ Updated: Cargo.toml [workspace.package]"
fi

# 3. Update src-tauri/Cargo.toml
if [ -f "$ROOT_DIR/src-tauri/Cargo.toml" ]; then
    sed -i -E 's/^version = "[0-9]+\.[0-9]+\.[0-9]+"/version = "'"$NEW_VERSION"'"/' "$ROOT_DIR/src-tauri/Cargo.toml"
    echo "  ✅ Updated: src-tauri/Cargo.toml"
fi

# 4. Update src-tauri/tauri.conf.json
if [ -f "$ROOT_DIR/src-tauri/tauri.conf.json" ]; then
    sed -i -E 's/"version": "[0-9]+\.[0-9]+\.[0-9]+"/"version": "'"$NEW_VERSION"'"/' "$ROOT_DIR/src-tauri/tauri.conf.json"
    echo "  ✅ Updated: src-tauri/tauri.conf.json"
fi

# 5. Update package.json
if [ -f "$ROOT_DIR/package.json" ]; then
    sed -i -E 's/"version": "[0-9]+\.[0-9]+\.[0-9]+"/"version": "'"$NEW_VERSION"'"/' "$ROOT_DIR/package.json"
    echo "  ✅ Updated: package.json"
fi

# 6. Update Windows Inno Setup & WiX Installer scripts
if [ -f "$ROOT_DIR/scripts/windows-installer.iss" ]; then
    sed -i -E 's/#define MyAppVersion "[0-9]+\.[0-9]+\.[0-9]+"/#define MyAppVersion "'"$NEW_VERSION"'"/' "$ROOT_DIR/scripts/windows-installer.iss"
    sed -i -E 's/OutputBaseFilename=auralock_[0-9]+\.[0-9]+\.[0-9]+_x64-setup/OutputBaseFilename=auralock_'"$NEW_VERSION"'_x64-setup/' "$ROOT_DIR/scripts/windows-installer.iss"
    sed -i -E 's/; Builds: auralock_[0-9]+\.[0-9]+\.[0-9]+_x64-setup.exe/; Builds: auralock_'"$NEW_VERSION"'_x64-setup.exe/' "$ROOT_DIR/scripts/windows-installer.iss"
    echo "  ✅ Updated: scripts/windows-installer.iss"
fi

if [ -f "$ROOT_DIR/scripts/windows-installer.wxs" ]; then
    sed -i -E 's/Version="[0-9]+\.[0-9]+\.[0-9]+\.0"/Version="'"$NEW_VERSION"'.0"/' "$ROOT_DIR/scripts/windows-installer.wxs"
    echo "  ✅ Updated: scripts/windows-installer.wxs"
fi

echo ""
echo "=================================================="
echo "  🎉 Version successfully synchronized everywhere to v${NEW_VERSION}!"
echo "  All shell scripts & Android build will dynamically read '${NEW_VERSION}'."
echo "=================================================="
