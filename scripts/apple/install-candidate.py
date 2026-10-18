#!/usr/bin/env python3
"""Install a prepared candidate without recording private device/signing identifiers."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import subprocess
from datetime import datetime, timezone

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
parser.add_argument('--launch', action='store_true')
args = parser.parse_args()
report = json.loads((args.directory / 'candidate.json').read_text())
if datetime.fromisoformat(report['profileExpiresAt']) <= datetime.now(timezone.utc):
    raise SystemExit('BLOCKED: development profile expired; prepare a newly signed candidate.')
app = args.directory / 'DerivedData/Build/Products/Release-iphoneos/WristBridge.app'
info = plistlib.loads((app / 'Info.plist').read_bytes())
assert hashlib.sha256((app / info['CFBundleExecutable']).read_bytes()).hexdigest() == report['binarySHA256']
subprocess.run(['codesign', '--verify', '--deep', '--strict', str(app)], check=True, capture_output=True)
result = subprocess.run(['xcrun', 'devicectl', 'list', 'devices', '--json-output', '-',
                         '--omit-deprecated-fields-in-json'], check=True, capture_output=True, text=True)
devices = [d for d in json.loads(result.stdout)['result']['devices']
           if d.get('properties', {}).get('hardware', {}).get('reality') == 'physical']
if len(devices) != 1:
    raise SystemExit('BLOCKED: connect one paired iPhone and unlock it.')
device = devices[0]['identifier']
result = subprocess.run(['xcrun', 'devicectl', 'device', 'install', 'app', '--device', device,
                         '--timeout', '30', str(app)], capture_output=True, text=True)
report['installation'] = 'PASS' if result.returncode == 0 else 'BLOCKED'
if result.returncode == 0 and args.launch:
    result = subprocess.run(['xcrun', 'devicectl', 'device', 'process', 'launch', '--device', device,
                             '--timeout', '20', '--terminate-existing', info['CFBundleIdentifier']],
                            capture_output=True, text=True)
    report['launch'] = 'PASS' if result.returncode == 0 else 'BLOCKED'
(args.directory / 'installation.json').write_text(json.dumps(report, indent=2) + '\n')
print('Installation: ' + report['installation'])
if 'launch' in report:
    print('Launch: ' + report['launch'])
if result.returncode != 0:
    raise SystemExit('BLOCKED: verify that the iPhone is connected, unlocked and trusts this Mac.')
