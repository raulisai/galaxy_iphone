#!/usr/bin/env python3
"""Regenerate the checked-in Xcode project without an external generator dependency."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "apps/ios"
OBJECTS = {}


def uid(key):
    return hashlib.sha256(key.encode()).hexdigest()[:24].upper()


def obj(key, isa, **fields):
    identifier = uid(key)
    OBJECTS[identifier] = dict(isa=isa, **fields)
    return identifier


def phase(key, isa, files):
    return obj(key, isa, buildActionMask="2147483647", files=files,
               runOnlyForDeploymentPostprocessing="0")


def encode(value, depth=0):
    if isinstance(value, dict):
        return "{\n" + "".join(
            "\t" * (depth + 1) + json.dumps(str(k)) + " = " + encode(v, depth + 1) + ";\n"
            for k, v in value.items()) + "\t" * depth + "}"
    if isinstance(value, list):
        return "(\n" + "".join("\t" * (depth + 1) + encode(v, depth + 1) + ",\n"
                               for v in value) + "\t" * depth + ")"
    return json.dumps(str(value))


sources = sorted(p.relative_to(ROOT / "WristBridge").as_posix()
                 for p in (ROOT / "WristBridge").rglob("*.swift"))
app_refs, source_builds = [], []
for path in sources:
    reference = obj("ref:" + path, "PBXFileReference", lastKnownFileType="sourcecode.swift",
                    path=path, sourceTree="<group>")
    app_refs.append(reference)
    source_builds.append(obj("build:" + path, "PBXBuildFile", fileRef=reference))
app_refs.append(obj("info", "PBXFileReference", lastKnownFileType="text.plist.xml",
                    path="Info.plist", sourceTree="<group>"))
privacy = obj("privacy", "PBXFileReference", lastKnownFileType="text.xml",
              path="Resources/PrivacyInfo.xcprivacy", sourceTree="<group>")
app_refs.append(privacy)
resource = obj("resource", "PBXBuildFile", fileRef=privacy)
test_ref = obj("testref", "PBXFileReference", lastKnownFileType="sourcecode.swift",
               path="WristBridgeUITests.swift", sourceTree="<group>")
test_build = obj("testbuild", "PBXBuildFile", fileRef=test_ref)
app_product = obj("appProduct", "PBXFileReference", explicitFileType="wrapper.application",
                  includeInIndex="0", path="WristBridge.app", sourceTree="BUILT_PRODUCTS_DIR")
test_product = obj("testProduct", "PBXFileReference", explicitFileType="wrapper.cfbundle",
                   includeInIndex="0", path="WristBridgeUITests.xctest", sourceTree="BUILT_PRODUCTS_DIR")
app_group = obj("appGroup", "PBXGroup", children=app_refs, path="WristBridge", sourceTree="<group>")
test_group = obj("testGroup", "PBXGroup", children=[test_ref], path="WristBridgeUITests", sourceTree="<group>")
products = obj("products", "PBXGroup", children=[app_product, test_product], name="Products", sourceTree="<group>")
signing = obj("signing", "PBXFileReference", lastKnownFileType="text.xcconfig", path="Signing.xcconfig", sourceTree="<group>")
main = obj("main", "PBXGroup", children=[app_group, test_group, signing, products], sourceTree="<group>")
package = obj("package", "XCLocalSwiftPackageReference", relativePath="../../packages/WristBridgeProtocol")
package_product = obj("packageProduct", "XCSwiftPackageProductDependency", package=package, productName="WristBridgeProtocol")
package_build = obj("packageBuild", "PBXBuildFile", productRef=package_product)
app_phases = [phase("appSources", "PBXSourcesBuildPhase", source_builds),
              phase("appFrameworks", "PBXFrameworksBuildPhase", [package_build]),
              phase("appResources", "PBXResourcesBuildPhase", [resource])]
test_phases = [phase("testSources", "PBXSourcesBuildPhase", [test_build]),
               phase("testFrameworks", "PBXFrameworksBuildPhase", []),
               phase("testResources", "PBXResourcesBuildPhase", [])]


def configurations(scope, extra):
    references = []
    for name in ["Debug", "Release"]:
        settings = dict(CLANG_ENABLE_MODULES="YES", SWIFT_VERSION="5.0",
                        IPHONEOS_DEPLOYMENT_TARGET="26.0", SDKROOT="iphoneos",
                        TARGETED_DEVICE_FAMILY="1", SUPPORTED_PLATFORMS="iphoneos iphonesimulator",
                        ENABLE_USER_SCRIPT_SANDBOXING="YES", **extra)
        settings.update(SWIFT_OPTIMIZATION_LEVEL="-Onone" if name == "Debug" else "-O",
                        ONLY_ACTIVE_ARCH="YES" if name == "Debug" else "NO",
                        SWIFT_ACTIVE_COMPILATION_CONDITIONS="DEBUG" if name == "Debug" else "",
                        DEBUG_INFORMATION_FORMAT="dwarf" if name == "Debug" else "dwarf-with-dsym",
                        ENABLE_TESTABILITY="YES" if name == "Debug" else "NO")
        fields = dict(buildSettings=settings, name=name)
        if scope != "project":
            fields["baseConfigurationReference"] = signing
        references.append(obj(scope + name, "XCBuildConfiguration", **fields))
    return obj(scope + "configs", "XCConfigurationList", buildConfigurations=references,
               defaultConfigurationIsVisible="0", defaultConfigurationName="Release")


app_configs = configurations("app", dict(
    PRODUCT_NAME="$(TARGET_NAME)", PRODUCT_BUNDLE_IDENTIFIER="$(WRISTBRIDGE_APP_BUNDLE_ID)",
    INFOPLIST_FILE="WristBridge/Info.plist", GENERATE_INFOPLIST_FILE="NO",
    MARKETING_VERSION="0.3.1", CURRENT_PROJECT_VERSION="8",
    LD_RUNPATH_SEARCH_PATHS=["$(inherited)", "@executable_path/Frameworks"], SWIFT_EMIT_LOC_STRINGS="YES"))
test_configs = configurations("tests", dict(
    PRODUCT_NAME="$(TARGET_NAME)", PRODUCT_BUNDLE_IDENTIFIER="$(WRISTBRIDGE_APP_BUNDLE_ID).uitests",
    GENERATE_INFOPLIST_FILE="YES", TEST_TARGET_NAME="WristBridge",
    LD_RUNPATH_SEARCH_PATHS=["$(inherited)", "@executable_path/Frameworks", "@loader_path/Frameworks"]))
project_configs = configurations("project", {})
app = obj("app", "PBXNativeTarget", buildConfigurationList=app_configs, buildPhases=app_phases,
          buildRules=[], dependencies=[], name="WristBridge", packageProductDependencies=[package_product],
          productName="WristBridge", productReference=app_product, productType="com.apple.product-type.application")
proxy = obj("proxy", "PBXContainerItemProxy", containerPortal=uid("project"), proxyType="1",
            remoteGlobalIDString=app, remoteInfo="WristBridge")
dependency = obj("dependency", "PBXTargetDependency", target=app, targetProxy=proxy)
tests = obj("tests", "PBXNativeTarget", buildConfigurationList=test_configs, buildPhases=test_phases,
            buildRules=[], dependencies=[dependency], name="WristBridgeUITests", productName="WristBridgeUITests",
            productReference=test_product, productType="com.apple.product-type.bundle.ui-testing")
project = obj("project", "PBXProject", attributes={
    "BuildIndependentTargetsInParallel": "YES", "LastUpgradeCheck": "2600",
    "TargetAttributes": {app: {"CreatedOnToolsVersion": "26.0"},
                         tests: {"CreatedOnToolsVersion": "26.0", "TestTargetID": app}}},
    buildConfigurationList=project_configs, compatibilityVersion="Xcode 14.0", developmentRegion="es",
    hasScannedForEncodings="0", knownRegions=["es", "en", "Base"], mainGroup=main,
    packageReferences=[package], productRefGroup=products, projectDirPath="", projectRoot="", targets=[app, tests])
(ROOT / "WristBridge.xcodeproj/project.pbxproj").write_text("// !$*UTF8*$!\n" + encode(dict(
    archiveVersion="1", classes={}, objectVersion="56", objects=OBJECTS, rootObject=project)) + "\n")


def buildable(identifier, name, product):
    return (f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{identifier}" '
            f'BuildableName="{product}" BlueprintName="{name}" ReferencedContainer="container:WristBridge.xcodeproj"/>')


app_ref = buildable(app, "WristBridge", "WristBridge.app")
test_ref_xml = buildable(tests, "WristBridgeUITests", "WristBridgeUITests.xctest")
scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2600" version="1.3">
 <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{app_ref}</BuildActionEntry></BuildActionEntries></BuildAction>
 <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO">{test_ref_xml}</TestableReference></Testables></TestAction>
 <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></LaunchAction>
 <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></ProfileAction>
 <AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
'''
(ROOT / "WristBridge.xcodeproj/xcshareddata/xcschemes/WristBridge.xcscheme").write_text(scheme)
print(f"Generated Xcode project: {len(sources)} app sources, UI test, local package.")
