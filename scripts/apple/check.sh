#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
python3 scripts/apple/validate_structure.py
bash scripts/apple/test-package.sh
if ! xcodebuild -version >/dev/null 2>&1; then
    echo 'BLOCKED: iOS build and UI tests require full Xcode with an iOS SDK.' >&2
    exit 2
fi
xcodebuild -version
xcodebuild -project apps/ios/WristBridge.xcodeproj -scheme WristBridge \
    -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
if [[ -n "${WRISTBRIDGE_TEST_DESTINATION:-}" ]]; then
    xcodebuild -project apps/ios/WristBridge.xcodeproj -scheme WristBridge \
        -destination "$WRISTBRIDGE_TEST_DESTINATION" CODE_SIGNING_ALLOWED=NO test
else
    echo 'NOT_RUN: set WRISTBRIDGE_TEST_DESTINATION to an installed iPhone simulator to run UI tests.'
fi
