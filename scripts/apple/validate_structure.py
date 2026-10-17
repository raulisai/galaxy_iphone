#!/usr/bin/env python3
"""Validate Apple source/configuration consistency. This is not an iOS build."""
import hashlib
import json
import plistlib
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def check(condition, message):
    if not condition:
        raise SystemExit("FAIL: " + message)
    print("PASS: " + message)


manifest = json.loads((ROOT / "Doc/manifest.json").read_text())
for name, expected in manifest["files"].items():
    data = (ROOT / "Doc" / name).read_bytes()
    check(len(data) == expected["bytes"] and hashlib.sha256(data).hexdigest() == expected["sha256"],
          "Original documentation intact: " + name)

contract = (ROOT / "Doc/01_CONTRATO_COMPARTIDO.md").read_text()
reserved = re.findall(r"`(6f06b8b1-[a-f0-9-]+)`", contract)
swift = (ROOT / "packages/WristBridgeProtocol/Sources/WristBridgeProtocol/BLEProfile.swift").read_text()
check(len(reserved) == 4 and all(swift.count(value) == 1 for value in reserved),
      "Swift UUID constants match the documented reservation")
matrix = (ROOT / "Doc/04_MATRIZ_60_TAREAS.md").read_text()
check(len(set(re.findall(r"\| (P[1-8]-\d{2}) \|", matrix))) == 60, "60 unique tasks in ownership matrix")
info = plistlib.loads((ROOT / "apps/ios/WristBridge/Info.plist").read_bytes())
check(info["NSAccessorySetupBluetoothServices"] == [reserved[0].upper()], "Picker service matches contract")
check(info["NSAccessorySetupBluetoothNames"] == ["WristBridge"], "Picker name proposal declared")
check(info["UIBackgroundModes"] == ["bluetooth-central"], "Only implemented background role enabled")
check(not any(key in info for key in ["NSMicrophoneUsageDescription", "NSContactsUsageDescription",
                                     "NSHealthShareUsageDescription", "NSHealthUpdateUsageDescription",
                                     "NSLocationAlwaysUsageDescription"]), "No premature sensitive permissions")
plistlib.loads((ROOT / "apps/ios/WristBridge/Resources/PrivacyInfo.xcprivacy").read_bytes())
project_path = ROOT / "apps/ios/WristBridge.xcodeproj/project.pbxproj"
project = json.loads(subprocess.check_output(["plutil", "-convert", "json", "-o", "-", str(project_path)]))
objects = project["objects"]
check(project["rootObject"] in objects, "Xcode root object resolves")
for key, item in objects.items():
    for field in ["fileRef", "productRef", "buildConfigurationList", "productReference", "mainGroup",
                  "productRefGroup", "target", "targetProxy", "containerPortal", "package"]:
        if field in item:
            check(item[field] in objects, f"Project reference {field} resolves ({key})")
    for field in ["children", "files", "buildConfigurations", "targets", "buildPhases", "dependencies",
                  "packageReferences", "packageProductDependencies"]:
        if field in item:
            check(all(value in objects for value in item[field]), f"Project list {field} resolves ({key})")
    if item["isa"] == "XCLocalSwiftPackageReference":
        check((ROOT / "apps/ios" / item["relativePath"] / "Package.swift").is_file(), "Local Swift package resolves")
    if item["isa"] == "PBXFileReference" and item.get("lastKnownFileType") == "sourcecode.swift":
        base = "WristBridgeUITests" if item["path"] == "WristBridgeUITests.swift" else "WristBridge"
        check((ROOT / "apps/ios" / base / item["path"]).is_file(), "Source file exists: " + item["path"])
scheme = ET.parse(ROOT / "apps/ios/WristBridge.xcodeproj/xcshareddata/xcschemes/WristBridge.xcscheme")
check(all(node.attrib["BlueprintIdentifier"] in objects for node in scheme.iter("BuildableReference")),
      "Shared scheme references existing targets")
sources = list((ROOT / "apps/ios").rglob("*.swift"))
subprocess.run(["swiftc", "-frontend", "-parse", *map(str, sources)], check=True)
check(True, "Apple Swift syntax parses (no SDK typechecking)")
before = project_path.read_bytes()
subprocess.run(["python3", str(ROOT / "scripts/apple/generate_project.py")], check=True)
check(before == project_path.read_bytes(), "Project regeneration is deterministic and complete")
