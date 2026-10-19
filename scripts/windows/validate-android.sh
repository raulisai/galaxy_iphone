#!/usr/bin/env bash
# Reproducible Android/Windows validation (P8-02 / P8-05, local).
#
# What it does
#   1. Runs the project's isolated-cache Gradle command (docs/windows/SEED.md, "Como operar en esta maquina"):
#        GRADLE_USER_HOME=build/g3, --project-cache-dir build/gradle-p1-project-3, --offline --no-daemon
#        --no-watch-fs --no-configuration-cache, scripts/windows/native-output.init.gradle and
#        -Pwristbridge.nativeBuildRoot=<NATIVE_ROOT> (outputs go outside the workspace drive).
#   2. Parses the JUnit XML results of every module, the Android Lint SARIF (XML as fallback for severities)
#      and the debug APK.
#   3. Writes evidence/<RUN>/windows/artifact.json and a sanitized evidence/<RUN>/windows/gradle-check.log.
#
# Usage (from Git Bash, any directory)
#   bash scripts/windows/validate-android.sh                       # RUN=windows-validate, NATIVE_ROOT in %TEMP%
#   RUN=windows-update-12 NATIVE_ROOT=/c/Users/me/AppData/Local/Temp/WristBridge-update-12 \
#       bash scripts/windows/validate-android.sh
#
# Environment (all optional)
#   RUN          evidence folder name under evidence/ (default: windows-validate). Re-running overwrites the two files.
#   NATIVE_ROOT  absolute local output directory for build outputs (default: ${TEMP:-/tmp}/WristBridge-<RUN>).
#   GRADLE_TASKS Gradle tasks (default: the three JVM test tasks + :apps:watch:lintDebug + :apps:watch:assembleDebug).
#   SKIP_GRADLE  1 = do not run Gradle, only parse the existing outputs under NATIVE_ROOT (the log is then NOT regenerated
#                and the artifact says "build": "NOT_RUN"). Useful to re-check results or to test this script.
#   GRADLEW      wrapper to use (default: ./gradlew.bat on Windows, ./gradlew elsewhere).
#
# Exit code: 0 only when Gradle passed (or SKIP_GRADLE=1 with results present), no test failed/errored and lint has no
# errors. No personal path, user name or home directory is written to the evidence (see sanitize in the Python block).
# Signature, installation and instrumented tests are NOT done here: the artifact marks them NOT_RUN.
set -u -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || { echo "cannot enter repository root" >&2; exit 2; }

pth() { cygpath -m "$1" 2>/dev/null || printf '%s' "$1"; }   # mixed path (F:/x) for Java/Python on Windows

RUN="${RUN:-windows-validate}"
case "$RUN" in *[!A-Za-z0-9._-]*|"") echo "RUN must match [A-Za-z0-9._-]+" >&2; exit 2;; esac
NATIVE_ROOT="${NATIVE_ROOT:-${TEMP:-${TMPDIR:-/tmp}}/WristBridge-$RUN}"
GRADLE_TASKS="${GRADLE_TASKS:-:core:protocol-kotlin:test :core:notification-domain:test :core:assistant-domain:test :apps:watch:lintDebug :apps:watch:assembleDebug}"
SKIP_GRADLE="${SKIP_GRADLE:-0}"
case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) DEFAULT_WRAPPER=./gradlew.bat;; *) DEFAULT_WRAPPER=./gradlew;; esac
GRADLEW="${GRADLEW:-$DEFAULT_WRAPPER}"

PY=""
for c in python3 python py; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then PY="$c"; break; fi
done
[ -n "$PY" ] || { echo "Python 3.8+ is required to parse the results" >&2; exit 2; }

OUT_DIR="$ROOT/evidence/$RUN/windows"
WORK="$(mktemp -d 2>/dev/null || echo "${TMPDIR:-/tmp}/validate-android.$$")"
mkdir -p "$WORK" "$OUT_DIR" "$NATIVE_ROOT" || { echo "cannot create output directories" >&2; exit 2; }
trap 'rm -rf "$WORK"' EXIT
RAW="$WORK/gradle-raw.log"
: > "$RAW"

GRADLE_RC=0
if [ "$SKIP_GRADLE" = "1" ]; then
  GRADLE_RC="skipped"
  echo "SKIP_GRADLE=1: parsing existing results under the native root only"
else
  [ -x "$GRADLEW" ] || [ -f "$GRADLEW" ] || { echo "wrapper not found: $GRADLEW" >&2; exit 2; }
  export GRADLE_USER_HOME="$(pth "$ROOT/build/g3")"
  if [ -z "${GRADLE_RO_DEP_CACHE:-}" ] && [ -d "$ROOT/build/gradle-android-p1-home/caches" ]; then
    export GRADLE_RO_DEP_CACHE="$(pth "$ROOT/build/gradle-android-p1-home/caches")"
  fi
  mkdir -p "$ROOT/build/g3" "$ROOT/build/gradle-p1-project-3"
  # shellcheck disable=SC2206
  TASKS=($GRADLE_TASKS)
  CMD=("$GRADLEW" "${TASKS[@]}"
       --gradle-user-home "$(pth "$ROOT/build/g3")"
       --project-cache-dir "$(pth "$ROOT/build/gradle-p1-project-3")"
       --offline --no-daemon --no-watch-fs --no-configuration-cache
       --init-script scripts/windows/native-output.init.gradle
       "-Pwristbridge.nativeBuildRoot=$(pth "$NATIVE_ROOT")")
  printf '%s ' "${CMD[@]}" > "$WORK/cmd.txt"
  echo "Running: ${CMD[*]}"
  "${CMD[@]}" > "$RAW" 2>&1
  GRADLE_RC=$?
  echo "Gradle exit code: $GRADLE_RC"
fi

# Git Bash would rewrite path-looking environment values for the native Python: switch that off.
export MSYS2_ENV_CONV_EXCL='*' MSYS_NO_PATHCONV=1
export V_CMDFILE="$(pth "$WORK/cmd.txt")" V_ROOT_SH="$ROOT" V_NATIVE_SH="$NATIVE_ROOT"
export V_ROOT="$(pth "$ROOT")" V_NATIVE="$(pth "$NATIVE_ROOT")" V_OUT="$(pth "$OUT_DIR")" V_RAW="$(pth "$RAW")" \
       V_RUN="$RUN" V_RC="$GRADLE_RC" V_SKIP="$SKIP_GRADLE" V_HOME_SH="${HOME:-}" V_USER="${USERNAME:-${USER:-}}"
"$PY" - <<'PY'
import datetime, glob, hashlib, json, os, re, subprocess, sys
import xml.etree.ElementTree as ET

E = os.environ
root, native, out, raw_path = E["V_ROOT"], E["V_NATIVE"], E["V_OUT"], E["V_RAW"]
rc, skipped = E["V_RC"], E["V_SKIP"] == "1"

def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as h:
        return h.read()

# ---------------------------------------------------------------- sanitize
def variants(p):
    p = (p or "").rstrip("/\\")
    if not p:
        return []
    fwd = p.replace("\\", "/")
    out_ = {p, fwd, fwd.replace("/", "\\"), fwd.replace("/", "\\\\")}
    m = re.match(r"^([A-Za-z]):/(.*)$", fwd)
    if m:                                  # Git Bash form /f/...
        out_.add("/%s/%s" % (m.group(1).lower(), m.group(2)))
    return sorted((v for v in out_ if v), key=len, reverse=True)

replacements = []
for value, token in ((native, "<native>"), (E.get("V_NATIVE_SH"), "<native>"), (root, "<repo>"), (E.get("V_ROOT_SH"), "<repo>"), (E.get("V_HOME_SH"), "<home>"),
                     (E.get("USERPROFILE"), "<home>"), (E.get("TEMP"), "<tmp>"), (E.get("TMP"), "<tmp>"),
                     (E.get("JAVA_HOME"), "<java-home>"), (E.get("ANDROID_HOME"), "<android-sdk>"),
                     (E.get("ANDROID_SDK_ROOT"), "<android-sdk>"), (E.get("LOCALAPPDATA"), "<localappdata>")):
    for v in variants(value):
        replacements.append((v, token))
replacements.sort(key=lambda t: len(t[0]), reverse=True)
generic = [
    (re.compile(r"[A-Za-z]:[\\/]+Users[\\/]+[^\\/\s\"':;,)]+", re.I), "<home>"),
    (re.compile(r"(?<![\w.])/(?:[a-z]/)?(?:Users|home)/[^/\s\"':;,)]+"), "<home>"),
]
user = (E.get("V_USER") or "").strip()

def sanitize(text):
    for old, new in replacements:
        text = text.replace(old, new)
    for rx, new in generic:
        text = rx.sub(new, text)
    if len(user) >= 3:
        text = re.sub(r"(?<![A-Za-z0-9])%s(?![A-Za-z0-9])" % re.escape(user), "<user>", text, flags=re.I)
    return text

def write_atomic(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as h:
        h.write(data)
    os.replace(tmp, path)

# ---------------------------------------------------------------- log
if not skipped:
    try:
        with open(raw_path, "r", encoding="utf-8", errors="replace") as h:
            body = h.read()
    except OSError:
        body = ""
    try:
        cmd = read(E["V_CMDFILE"]).strip()
    except OSError:
        cmd = ""
    header = "$ %s\n" % sanitize(cmd)
    write_atomic(os.path.join(out, "gradle-check.log"), header + sanitize(body))

# ---------------------------------------------------------------- gradle file
kts = read(os.path.join(root, "apps/watch/build.gradle.kts"))
def grab(pattern):
    m = re.search(pattern, kts)
    return m.group(1) if m else None
version_name = grab(r'versionName\s*=\s*"([^"]+)"')
version_code = grab(r"versionCode\s*=\s*(\d+)")
app_id = grab(r'applicationId\s*=\s*"([^"]+)"')

def git(*args):
    try:
        r = subprocess.run(["git", "-C", root, *args], capture_output=True, text=True, timeout=60)
        return r.stdout.strip() if r.returncode == 0 else ""
    except Exception:
        return ""
commit = git("rev-parse", "HEAD")
if not re.fullmatch(r"[0-9a-f]{40}", commit or ""):
    commit = "unknown"
dirty = bool(git("status", "--porcelain", "--untracked-files=no"))

# ---------------------------------------------------------------- junit
modules = [("protocol-kotlin", "core/protocol-kotlin"), ("notification-domain", "core/notification-domain"),
           ("assistant-domain", "core/assistant-domain"), ("watch", "apps/watch")]
jvm, totals = [], {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
for name, part in modules:
    files = sorted(glob.glob(os.path.join(native, part, "test-results", "*", "TEST-*.xml")))
    if not files:
        continue
    acc = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for f in files:
        try:
            suite = ET.parse(f).getroot()
        except (ET.ParseError, OSError):
            acc["errors"] += 1
            continue
        for s in ([suite] if suite.tag == "testsuite" else suite.iter("testsuite")):
            for k in acc:
                acc[k] += int(s.get(k, "0") or 0)
    jvm.append({"module": name, "suites": len(files), **acc})
    for k in totals:
        totals[k] += acc[k]

# ---------------------------------------------------------------- lint
reports = os.path.join(native, "apps/watch/reports")
sarif_path = os.path.join(reports, "lint-results-debug.sarif")
xml_path = os.path.join(reports, "lint-results-debug.xml")
xml_sev = {}
if os.path.isfile(xml_path):
    try:
        for issue in ET.parse(xml_path).getroot().iter("issue"):
            xml_sev.setdefault(issue.get("id"), issue.get("severity"))
    except (ET.ParseError, OSError):
        pass
issues, lint_source = [], None
if os.path.isfile(sarif_path):
    lint_source = "sarif"
    try:
        run = json.loads(read(sarif_path))["runs"][0]
        rules = run.get("tool", {}).get("driver", {}).get("rules", [])
        for r in run.get("results", []):
            rid = r.get("ruleId") or "unknown"
            level = r.get("level")
            if not level and isinstance(r.get("ruleIndex"), int) and 0 <= r["ruleIndex"] < len(rules):
                level = rules[r["ruleIndex"]].get("defaultConfiguration", {}).get("level")
            sev = {"error": "Error", "warning": "Warning", "note": "Information"}.get(level or "", None) \
                or xml_sev.get(rid) or "Warning"
            issues.append({"id": rid, "severity": sev})
    except (ValueError, KeyError, IndexError, OSError):
        lint_source = "sarif-unreadable"
elif xml_sev:
    lint_source = "xml"
    try:
        for issue in ET.parse(xml_path).getroot().iter("issue"):
            issues.append({"id": issue.get("id"), "severity": issue.get("severity")})
    except (ET.ParseError, OSError):
        lint_source = "xml-unreadable"
def count(*sev):
    return sum(1 for i in issues if i["severity"] in sev)
lint = {"source": lint_source or "missing", "errors": count("Error", "Fatal"), "warnings": count("Warning"),
        "informational": count("Information"), "issues": issues}

# ---------------------------------------------------------------- apk
apks = sorted(glob.glob(os.path.join(native, "apps/watch/outputs/apk/debug/*.apk")))
apk_info = {"apkPath": None, "sha256": None, "sizeBytes": None}
if apks:
    apk = apks[0]
    h = hashlib.sha256()
    with open(apk, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    apk_info = {"apkPath": sanitize(apk).replace("\\", "/"), "sha256": h.hexdigest(), "sizeBytes": os.path.getsize(apk)}

# ---------------------------------------------------------------- verdict
if skipped:
    build = "NOT_RUN"
elif rc == "0" and apks:
    build = "PASS"
else:
    build = "FAIL"
tests_ok = bool(jvm) and totals["failures"] == 0 and totals["errors"] == 0
lint_ok = lint["source"] in ("sarif", "xml") and lint["errors"] == 0

artifact = {
    "capturedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "run": E["V_RUN"],
    "versionName": version_name,
    "versionCode": int(version_code) if version_code else None,
    "applicationId": app_id,
    "gitCommit": commit,
    "gitDirty": dirty,
    **apk_info,
    "build": build,
    "gradleExitCode": None if skipped else int(rc) if rc.lstrip("-").isdigit() else rc,
    "jvmTests": jvm,
    "jvmTestsTotal": totals,
    "jvmTestsResult": "PASS" if tests_ok else "FAIL",
    "lint": lint,
    "lintResult": "PASS" if lint_ok else "FAIL",
    "signature": "NOT_RUN",
    "installation": "NOT_RUN",
    "instrumentedTests": "NOT_RUN",
    "note": "Produced by scripts/windows/validate-android.sh; paths are sanitized (<native>, <repo>, <home>).",
}
write_atomic(os.path.join(out, "artifact.json"), json.dumps(artifact, indent=2, ensure_ascii=False) + "\n")
print("artifact.json: build=%s tests=%d failures=%d errors=%d skipped=%d lintErrors=%d lintWarnings=%d" % (
    build, totals["tests"], totals["failures"], totals["errors"], totals["skipped"], lint["errors"], lint["warnings"]))
sys.exit(0 if (build in ("PASS", "NOT_RUN") and tests_ok and lint_ok) else 1)
PY
exit $?
