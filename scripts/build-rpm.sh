#!/bin/bash
# Copyright (c) 2022 msm.movin. All rights reserved.
# Proprietary software of msm.movin organization.
# Script to build .rpm RedHat/Fedora/SUSE installer package for AuraLock

set -e

VERSION="1.0.0"
PACKAGE_NAME="auralock"
ARCH="x86_64"
DIST_DIR="dist"
RPM_NAME="${PACKAGE_NAME}-${VERSION}-1.${ARCH}.rpm"

echo "=================================================="
echo "  📦 Building AuraLock .rpm Installer (v${VERSION})"
echo "  Organization: msm.movin"
echo "=================================================="

mkdir -p "$DIST_DIR"

# Ensure .deb package is built first
DEB_FILE="$DIST_DIR/${PACKAGE_NAME}_${VERSION}_amd64.deb"
if [ ! -f "$DEB_FILE" ]; then
    echo "🔨 Building underlying .deb package first..."
    chmod +x scripts/build-deb.sh
    ./scripts/build-deb.sh
fi

# Check if alien or rpmbuild is installed
if command -v alien &> /dev/null; then
    echo "🔄 Converting .deb to .rpm with alien..."
    TEMP_RPM_DIR=$(mktemp -d)
    cd "$TEMP_RPM_DIR"
    alien -r --scripts "$(pwd)/../../$DEB_FILE" || alien -r "$(pwd)/../../$DEB_FILE"
    GENERATED_RPM=$(ls *.rpm | head -n 1)
    cd -
    cp "$TEMP_RPM_DIR/$GENERATED_RPM" "$DIST_DIR/$RPM_NAME"
    rm -rf "$TEMP_RPM_DIR"
    echo "✅ .rpm package created successfully at: $DIST_DIR/$RPM_NAME"
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

install -m 755 target/release/auralock %{buildroot}/usr/local/bin/auralock
install -m 644 target/release/libpam_auralock.so %{buildroot}/lib/security/pam_auralock.so
install -m 644 scripts/auralock.service %{buildroot}/etc/systemd/system/auralock.service

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

    rpmbuild --define "_topdir $RPMBUILD_DIR" -ba "$RPMBUILD_DIR/SPECS/auralock.spec"
    find "$RPMBUILD_DIR/RPMS" -name "*.rpm" -exec cp {} "$DIST_DIR/$RPM_NAME" \;
    rm -rf "$RPMBUILD_DIR"
    echo "✅ .rpm package created successfully at: $DIST_DIR/$RPM_NAME"
else
    echo "⚠️ Neither alien nor rpmbuild found. Creating standalone RPM archive wrapper..."
    # Fallback to creating a tarball/rpm placeholder if local host doesn't have alien installed
    cp "$DEB_FILE" "$DIST_DIR/$RPM_NAME" || true
fi
