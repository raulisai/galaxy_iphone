#!/bin/bash
# Prepare a local signed iPhone candidate. Does not install or publish it.
set -euo pipefail
cd "$(dirname "$0")/../.."
output="${1:-/tmp/WristBridge-iPhone-candidate}"
mkdir -p "$output"
output="$(cd "$output" && pwd)"
repository="$(pwd)"
case "$output/" in "$repository/"*) echo 'Use an output directory outside the repository (contains local signing material).' >&2; exit 1 ;; esac
xcodebuild -project apps/ios/WristBridge.xcodeproj -scheme WristBridge \
    -configuration Release -destination 'generic/platform=iOS' \
    -derivedDataPath "$output/DerivedData" -allowProvisioningUpdates build > "$output/build.local.log" 2>&1
app="$output/DerivedData/Build/Products/Release-iphoneos/WristBridge.app"
codesign --verify --deep --strict "$app" > "$output/signature.local.log" 2>&1
# Keep the signed app/profile and raw logs local; the report deliberately omits identity.
python3 - "$app" "$output" <<'PY'
import hashlib,json,plistlib,subprocess,sys
from datetime import timezone
from pathlib import Path
app,out=map(Path,sys.argv[1:])
info=plistlib.loads((app/'Info.plist').read_bytes())
sources=[]
for base in ['apps/ios/WristBridge','apps/ios/WristBridgeUITests',
             'packages/WristBridgeProtocol/Sources','packages/WristBridgeProtocol/Tests','scripts/apple']:
    sources.extend(p for p in Path(base).rglob('*') if p.is_file())
sources.append(Path('apps/ios/WristBridge.xcodeproj/project.pbxproj'))
manifest={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(sources)}
(out/'source-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
binary=app/info['CFBundleExecutable']
profile = subprocess.run(['security','cms','-D','-i',str(app/'embedded.mobileprovision')],capture_output=True,check=True)
expiration = plistlib.loads(profile.stdout)['ExpirationDate'].replace(tzinfo=timezone.utc).isoformat()
assert b'--lab-reconnect-once' not in binary.read_bytes(), 'Debug-only lab hook leaked into Release'
report={'version':info['CFBundleShortVersionString'],'build':info['CFBundleVersion'],
        'configuration':'Release','signedBuild':'PASS','signatureVerification':'PASS',
        'profileExpiresAt':expiration,'debugLabHookAbsent':True,
        'installation':'NOT_RUN','physicalAcceptance':'NOT_RUN',
        'binarySHA256':hashlib.sha256(binary.read_bytes()).hexdigest(),
        'sourceManifestSHA256':hashlib.sha256((out/'source-manifest.json').read_bytes()).hexdigest(),
        'baseCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'workspaceChangesPresent':bool(subprocess.check_output(['git','status','--porcelain'],text=True).strip()),
        'toolchain':subprocess.check_output(['xcodebuild','-version'],text=True).strip(),
        'deploymentTarget':'iOS 26.0','profileRevision':'p1-lab-1'}
(out/'candidate.json').write_text(json.dumps(report,indent=2)+'\n')
print('PASS: signed local iPhone candidate '+report['version']+' ('+report['build']+')')
print('Candidate report: '+str(out/'candidate.json'))
PY
