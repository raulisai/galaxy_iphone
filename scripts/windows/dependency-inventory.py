#!/usr/bin/env python3
"""Offline dependency inventory of the Windows/Kotlin modules (P8-05, local input).

IMPORTANT: this is an INVENTORY, not a formal SBOM (no CycloneDX/SPDX, no hashes of artifacts, no vulnerability
data, licences only as written in the POMs). The joint SBOM is composed later together with the Apple inventory.

Sources, all read offline and read-only:
  * gradle/libs.versions.toml                  -> aliases, versions, plugins
  * apps/watch and core/*/build.gradle.kts     -> declared (direct) dependencies per configuration
  * Gradle module caches (modules-2/files-2.1) -> BOM-managed versions, POM licences and every module version present
  * optional --resolved FILE                   -> output of `gradlew :apps:watch:dependencies` (and the core modules)
                                                  to mark what Gradle really resolved (the cache alone cannot tell)

Each entry is group:name:version with:
  declared      true when a build file asks for it directly (version comes from the file, the toml, a BOM or the Kotlin plugin)
  resolved      true / false / null (null = no --resolved file given, so resolution is unknown)
  inCache       true when that exact version exists in a scanned cache (artifactFiles lists its file extensions)
  licence       the <license><name> of the POM (or of its parent POM) or "unverified"
  licenceSource "pom" | "parent-pom" | "none"
Entries that are only in the cache (not declared) may belong to build tooling or to other projects sharing the cache;
they are listed under "cacheOnly" to avoid claiming they ship in the APK.

Usage:
  python scripts/windows/dependency-inventory.py                      # writes docs/provenance/windows-dependencies.json
  python scripts/windows/dependency-inventory.py --resolved deps.txt  # also mark Gradle-resolved versions
  python scripts/windows/dependency-inventory.py --cache DIR ...      # extra files-2.1 directories (repeatable)
  python scripts/windows/dependency-inventory.py --output FILE   (--root DIR to inventory another checkout)
Cache roots scanned by default (those that exist): build/g3, build/gradle-android-p1-home, $GRADLE_USER_HOME, ~/.gradle.
The output has no absolute or home paths and no timestamp: the same inputs give the same bytes (idempotent).
"""
import argparse
import glob
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
CONFIGS = ("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation", "testRuntimeOnly",
           "debugImplementation", "androidTestImplementation", "ksp", "kapt")


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as h:
        return h.read()


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


# ------------------------------------------------------------------------------------------------ toml
def parse_toml(path):
    """Tiny parser for the subset used by libs.versions.toml (inline tables, one entry per line)."""
    versions, libs, plugins = {}, {}, {}
    section = None
    for raw in read(path).splitlines():
        line = raw.split("#", 1)[0].strip() if not raw.strip().startswith('"') else raw.strip()
        if not line:
            continue
        m = re.match(r"^\[(\w+)\]$", line)
        if m:
            section = m.group(1)
            continue
        m = re.match(r'^([\w.-]+)\s*=\s*(.+)$', line)
        if not m or section is None:
            continue
        key, value = m.group(1), m.group(2).strip()
        if section == "versions":
            versions[key] = value.strip('"')
        elif section in ("libraries", "plugins"):
            fields = dict(re.findall(r'([\w.]+)\s*=\s*"([^"]*)"', value))
            (libs if section == "libraries" else plugins)[key] = fields
    return versions, libs, plugins


def toml_version(entry, versions):
    if "version" in entry:
        return entry["version"]
    if "version.ref" in entry:
        return versions.get(entry["version.ref"])
    return None


# ------------------------------------------------------------------------------------------------ caches
class Caches:
    def __init__(self, roots):
        self.roots = roots  # list of (label, path)

    def module_dir(self, group, name, version):
        for label, root in self.roots:
            d = os.path.join(root, group, name, version)
            if os.path.isdir(d):
                yield label, d

    def files_of(self, group, name, version):
        exts = set()
        for _, d in self.module_dir(group, name, version):
            for f in glob.glob(os.path.join(d, "*", "*")):
                base = os.path.basename(f)
                if "-sources" in base or "-javadoc" in base:
                    continue
                exts.add(os.path.splitext(base)[1].lstrip(".") or "?")
        return sorted(exts)

    def pom(self, group, name, version):
        for _, d in self.module_dir(group, name, version):
            hits = glob.glob(os.path.join(d, "*", "%s-%s.pom" % (name, version)))
            if hits:
                return hits[0]
        return None

    def scan(self):
        """Yield (group, name, version, label) for every module version directory in the caches."""
        for label, root in self.roots:
            for g in sorted(os.listdir(root)):
                gd = os.path.join(root, g)
                if not os.path.isdir(gd):
                    continue
                for n in sorted(os.listdir(gd)):
                    nd = os.path.join(gd, n)
                    if not os.path.isdir(nd):
                        continue
                    for v in sorted(os.listdir(nd)):
                        if os.path.isdir(os.path.join(nd, v)):
                            yield g, n, v, label


def strip_ns(tag):
    return tag.split("}", 1)[-1]


def pom_info(path):
    """Return (licences, parent(group,name,version) or None, properties, managed deps) of a POM."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return [], None, {}, []
    lic, parent, props, managed = [], None, {}, []
    for child in root:
        t = strip_ns(child.tag)
        if t == "licenses":
            for l in child:
                for f in l:
                    if strip_ns(f.tag) == "name" and (f.text or "").strip():
                        lic.append(f.text.strip())
        elif t == "parent":
            p = {strip_ns(f.tag): (f.text or "").strip() for f in child}
            if p.get("groupId") and p.get("artifactId") and p.get("version"):
                parent = (p["groupId"], p["artifactId"], p["version"])
        elif t == "properties":
            props = {strip_ns(f.tag): (f.text or "").strip() for f in child}
        elif t == "dependencyManagement":
            for d in child.iter():
                if strip_ns(d.tag) == "dependency":
                    f = {strip_ns(x.tag): (x.text or "").strip() for x in d}
                    if f.get("groupId") and f.get("artifactId") and f.get("version"):
                        managed.append((f["groupId"], f["artifactId"], f["version"]))
    return lic, parent, props, managed


def licence_of(caches, g, n, v):
    seen = set()
    cur = (g, n, v)
    source = "pom"
    while cur and cur not in seen and len(seen) < 4:
        seen.add(cur)
        pom = caches.pom(*cur)
        if not pom:
            break
        lic, parent, _, _ = pom_info(pom)
        if lic:
            return "; ".join(sorted(set(lic))), source
        cur, source = parent, "parent-pom"
    return "unverified", "none"


def bom_versions(caches, g, n, v):
    pom = caches.pom(g, n, v)
    out = {}
    if pom:
        for mg, mn, mv in pom_info(pom)[3]:
            out["%s:%s" % (mg, mn)] = mv
    return out


# ------------------------------------------------------------------------------------------------ build files
DEP_RE = re.compile(r"^\s*(%s)\s*\((.+)\)\s*$" % "|".join(CONFIGS))


def parse_build_file(path, libs, versions):
    """Return a list of dicts {config, kind, ...} for the dependencies block lines of one build file."""
    found = []
    for line in read(path).splitlines():
        m = DEP_RE.match(line.split("//", 1)[0])
        if not m:
            continue
        config, arg = m.group(1), m.group(2).strip()
        platform = False
        pm = re.match(r"^platform\((.+)\)$", arg)
        if pm:
            platform, arg = True, pm.group(1).strip()
        if arg.startswith("project("):
            found.append({"config": config, "kind": "project", "target": re.search(r'"([^"]+)"', arg).group(1)})
        elif arg.startswith("libs."):
            alias = arg[len("libs."):].replace(".", "-")
            entry = libs.get(alias)
            if entry is None and alias.startswith("plugins-"):
                continue
            if entry is None:
                found.append({"config": config, "kind": "unknown", "text": arg})
                continue
            gm = entry.get("module", ":").split(":", 1)
            found.append({"config": config, "kind": "module", "group": gm[0], "name": gm[1],
                          "version": toml_version(entry, versions), "platform": platform, "via": "toml:" + alias})
        elif arg.startswith("kotlin("):
            found.append({"config": config, "kind": "module", "group": "org.jetbrains.kotlin",
                          "name": "kotlin-" + re.search(r'"([^"]+)"', arg).group(1),
                          "version": "@kotlin", "platform": False, "via": "kotlin()"})
        else:
            sm = re.match(r'^"([^":]+):([^":]+)(?::([^"]+))?"$', arg)
            if sm:
                found.append({"config": config, "kind": "module", "group": sm.group(1), "name": sm.group(2),
                              "version": sm.group(3), "platform": platform, "via": "build-file"})
            else:
                found.append({"config": config, "kind": "unknown", "text": arg})
    return found


# ------------------------------------------------------------------------------------------------ resolved
RES_RE = re.compile(r"([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-+]+)(?:\s*->\s*([A-Za-z0-9_.\-+]+))?")


def parse_resolved(path):
    out = {}
    for line in read(path).splitlines():
        if not re.search(r"[+\\|]--- ", line):
            continue
        m = RES_RE.search(line.split("--- ", 1)[1])
        if m:
            g, n, v, r = m.groups()
            out[(g, n, r or v)] = True
    return out


# ------------------------------------------------------------------------------------------------ main
def default_caches(extra):
    candidates = [("project-g3", os.path.join(ROOT, "build", "g3")),
                  ("project-p1-home", os.path.join(ROOT, "build", "gradle-android-p1-home")),
                  ("gradle-user-home", os.environ.get("GRADLE_USER_HOME", "")),
                  ("user-gradle", os.path.join(os.path.expanduser("~"), ".gradle"))]
    roots, seen = [], set()
    for label, base in candidates + [("extra", e) for e in extra]:
        if not base:
            continue
        for cand in (base, os.path.join(base, "caches", "modules-2", "files-2.1"), os.path.join(base, "modules-2", "files-2.1")):
            if os.path.basename(cand) == "files-2.1" and os.path.isdir(cand):
                key = os.path.normcase(os.path.realpath(cand))
                if key not in seen:
                    seen.add(key)
                    roots.append((label, cand))
    return roots


def main(argv=None):
    global ROOT
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--root", default=ROOT, help="repository root (default: two levels above this script)")
    ap.add_argument("--output", help="default: <root>/docs/provenance/windows-dependencies.json")
    ap.add_argument("--resolved", help="text output of `gradlew <module>:dependencies` to mark resolved versions")
    ap.add_argument("--cache", action="append", default=[], help="extra Gradle modules-2/files-2.1 directory")
    args = ap.parse_args(argv)
    ROOT = os.path.abspath(args.root)
    args.output = args.output or os.path.join(ROOT, "docs", "provenance", "windows-dependencies.json")

    toml_path = os.path.join(ROOT, "gradle", "libs.versions.toml")
    if not os.path.isfile(toml_path):
        print("gradle/libs.versions.toml not found", file=sys.stderr)
        return 2
    versions, libs, plugins = parse_toml(toml_path)
    caches = Caches(default_caches(args.cache))

    modules = {"apps/watch": os.path.join(ROOT, "apps", "watch", "build.gradle.kts")}
    for f in sorted(glob.glob(os.path.join(ROOT, "core", "*", "build.gradle.kts"))):
        modules["core/" + os.path.basename(os.path.dirname(f))] = f

    kotlin_version = versions.get("kotlin")
    entries, projects, unknown, inputs = {}, [], [], {"gradle/libs.versions.toml": sha256_of(toml_path)}
    bom_cache = {}
    for mod, path in sorted(modules.items()):
        if not os.path.isfile(path):
            continue
        inputs[mod + "/build.gradle.kts"] = sha256_of(path)
        deps = parse_build_file(path, libs, versions)
        boms = [d for d in deps if d["kind"] == "module" and d["platform"]]
        managed = {}
        for b in boms:
            if b["version"]:
                key = (b["group"], b["name"], b["version"])
                if key not in bom_cache:
                    bom_cache[key] = bom_versions(caches, *key)
                managed.update(bom_cache[key])
        for d in deps:
            if d["kind"] == "project":
                projects.append({"module": mod, "configuration": d["config"], "project": d["target"]})
                continue
            if d["kind"] == "unknown":
                unknown.append({"module": mod, "configuration": d["config"], "text": d["text"]})
                continue
            ga = "%s:%s" % (d["group"], d["name"])
            version, how = d["version"], d["via"]
            if version == "@kotlin":
                version, how = kotlin_version, "kotlin-plugin version"
            if not version and ga in managed:
                version, how = managed[ga], "BOM"
            if d["platform"]:
                how += " (platform/BOM)"
            key = (d["group"], d["name"], version or "unresolved")
            e = entries.setdefault(key, {"group": key[0], "name": key[1], "version": key[2], "declared": True,
                                         "usedBy": [], "versionSource": how})
            e["usedBy"].append({"module": mod, "configuration": d["config"]})

    resolved = parse_resolved(args.resolved) if args.resolved else None
    if resolved:
        for g, n, v in resolved:
            entries.setdefault((g, n, v), {"group": g, "name": n, "version": v, "declared": False, "usedBy": [],
                                           "versionSource": "gradle dependencies output"})

    cache_only = {}
    for g, n, v, label in caches.scan():
        key = (g, n, v)
        if key in entries:
            continue
        cache_only.setdefault(key, {"group": g, "name": n, "version": v, "declared": False})

    def finish(e):
        g, n, v = e["group"], e["name"], e["version"]
        e["inCache"] = bool(list(caches.module_dir(g, n, v)))
        e["artifactFiles"] = caches.files_of(g, n, v)
        e["resolved"] = None if resolved is None else ((g, n, v) in resolved)
        e["licence"], e["licenceSource"] = licence_of(caches, g, n, v) if e["inCache"] else ("unverified", "none")
        if "usedBy" in e:
            e["usedBy"] = sorted(e["usedBy"], key=lambda u: (u["module"], u["configuration"]))
        return e

    ordered = lambda d: [finish(d[k]) for k in sorted(d)]
    doc = {
        "kind": "dependency-inventory",
        "notSbom": "Inventory of declared and cached Gradle modules; it is not a formal SBOM (no CycloneDX/SPDX, "
                   "no artifact hashes, no vulnerability data). Licences are copied from POMs and not verified.",
        "generatedBy": "scripts/windows/dependency-inventory.py",
        "limitations": [
            "Transitive dependencies and implicit ones (for example kotlin-stdlib added by the Kotlin plugin) are not "
            "derived unless a Gradle dependencies report is passed with --resolved.",
            "cacheOnly lists module versions present in the scanned Gradle caches, including build tooling and other "
            "projects; presence in a cache does not mean the module ships in the APK.",
            "licence is the POM <license><name> (or its parent POM's); \"unverified\" means none was readable offline.",
        ],
        "inputs": inputs,
        "cacheRootsScanned": [label for label, _ in caches.roots],
        "resolutionKnown": resolved is not None,
        "plugins": [{"alias": k, "id": v.get("id"), "version": toml_version(v, versions)} for k, v in sorted(plugins.items())],
        "projectDependencies": sorted(projects, key=lambda p: (p["module"], p["configuration"], p["project"])),
        "unparsedDependencyLines": unknown,
        "dependencies": ordered(entries),
        "cacheOnly": ordered(cache_only),
    }
    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    tmp = args.output + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as h:
        h.write(json.dumps(doc, indent=2, ensure_ascii=False, sort_keys=True) + "\n")
    os.replace(tmp, args.output)
    try:
        shown = os.path.relpath(args.output, ROOT).replace("\\", "/")
    except ValueError:  # different drive on Windows
        shown = os.path.basename(args.output)
    print("%d declared/resolved, %d cache-only, %d with licence unverified -> %s" % (
        len(doc["dependencies"]), len(doc["cacheOnly"]),
        sum(1 for e in doc["dependencies"] if e["licence"] == "unverified"), shown))
    return 0


if __name__ == "__main__":
    sys.exit(main())
