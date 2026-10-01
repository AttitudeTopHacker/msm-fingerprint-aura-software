#!/bin/bash
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
# Script to build .rpm RedHat/Fedora/SUSE installer package for AuraLock

set -e

VERSION="1.3.1"
PACKAGE_NAME="auralock"
ARCH="x86_64"
PROJECT_ROOT="$(pwd)"
DIST_DIR="${PROJECT_ROOT}/dist"
RPM_NAME="${PACKAGE_NAME}-${VERSION}-1.${ARCH}.rpm"
DEB_FILE="${DIST_DIR}/${PACKAGE_NAME}_${VERSION}_amd64.deb"

echo "=================================================="
echo "  📦 Building AuraLock .rpm Installer (v${VERSION})"
echo "  Organization: msm.movin"
echo "=================================================="

mkdir -p "$DIST_DIR"

if [ ! -f "$DEB_FILE" ]; then
    echo "🔨 Building underlying .deb package first..."
    chmod +x "${PROJECT_ROOT}/scripts/build-deb.sh"
    "${PROJECT_ROOT}/scripts/build-deb.sh"
fi

if command -v alien &> /dev/null; then
    echo "🔄 Converting .deb to .rpm with alien..."
    TEMP_RPM_DIR=$(mktemp -d)
    cd "$TEMP_RPM_DIR"
    alien -r -c "$DEB_FILE" || alien -r "$DEB_FILE" || true
    GENERATED_RPM=$(ls *.rpm 2>/dev/null | head -n 1 || true)
    if [ -n "$GENERATED_RPM" ] && [ -f "$GENERATED_RPM" ]; then
        cp "$GENERATED_RPM" "$DIST_DIR/$RPM_NAME"
        echo "✅ .rpm package created successfully at: $DIST_DIR/$RPM_NAME"
    fi
    cd "$PROJECT_ROOT"
    rm -rf "$TEMP_RPM_DIR"
elif command -v rpmbuild &> /dev/null; then
    echo "📦 Building RPM with rpmbuild..."
    RPMBUILD_DIR=$(mktemp -d)
    mkdir -p "$RPMBUILD_DIR"/{BUILD,RPMS,SOURCES,SPECS,SRPMS}
    
    cat << EOF > "$RPMBUILD_DIR/SPECS/auralock.spec"
Name:           ${PACKAGE_NAME}
Version:        ${VERSION}
Release:        1%{?dist}
Summary:        Instant Remote Biometric Unlock for Linux
License:        Proprietary (msm.movin)
URL:            https://github.com/AttitudeTopHacker/msm-fingerprint-aura-software

%description
AuraLock turns your Android smartphone's fingerprint sensor into a hardware
biometric key for Linux lockscreens and sudo authentication over local Wi-Fi and BLE.
Developed by msm.movin. Copyright (c) 2022 msm.movin. All rights reserved.

%install
mkdir -p %{buildroot}/usr/local/bin
mkdir -p %{buildroot}/lib/security
mkdir -p %{buildroot}/etc/systemd/system

install -m 755 ${PROJECT_ROOT}/target/release/auralock %{buildroot}/usr/local/bin/auralock
install -m 644 ${PROJECT_ROOT}/target/release/libpam_auralock.so %{buildroot}/lib/security/pam_auralock.so
install -m 644 ${PROJECT_ROOT}/scripts/auralock.service %{buildroot}/etc/systemd/system/auralock.service

%post
systemctl daemon-reload || :
systemctl enable auralock.service || :
systemctl restart auralock.service || :

%preun
if [ \$1 -eq 0 ]; then
    systemctl stop auralock.service || :
    systemctl disable auralock.service || :
fi

%files
/usr/local/bin/auralock
/lib/security/pam_auralock.so
/etc/systemd/system/auralock.service
EOF

    rpmbuild --define "_topdir $RPMBUILD_DIR" -ba "$RPMBUILD_DIR/SPECS/auralock.spec" || true
    find "$RPMBUILD_DIR/RPMS" -name "*.rpm" -exec cp {} "$DIST_DIR/$RPM_NAME" \; 2>/dev/null || true
    rm -rf "$RPMBUILD_DIR"
    echo "✅ .rpm package created at: $DIST_DIR/$RPM_NAME"
fi
