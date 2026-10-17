#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
developer_root="${DEVELOPER_DIR:-$(xcode-select -p)}"
testing_frameworks="$developer_root/Library/Developer/Frameworks"
testing_plugins="$developer_root/usr/lib/swift/host/plugins/testing"
if [[ "$developer_root" == */CommandLineTools && -d "$testing_frameworks/Testing.framework" && -d "$testing_plugins" ]]; then
    echo 'CLT compatibility: native SwiftPM backend with explicit bundled Testing framework/plugin paths.'
    swift test --package-path packages/WristBridgeProtocol --build-system native \
        -Xswiftc -F -Xswiftc "$testing_frameworks" \
        -Xswiftc -plugin-path -Xswiftc "$testing_plugins" \
        -Xlinker -rpath -Xlinker "$testing_frameworks"
else
    swift test --package-path packages/WristBridgeProtocol
fi
