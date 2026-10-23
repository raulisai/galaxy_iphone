#!/usr/bin/env bash
# Copies every WristBridge diagnostic log off the watch (wireless debugging must be on).
# Usage: scripts/windows/pull-watch-logs.sh [output-dir]
# Output: wb*.log (app log: events, warnings, errors, crashes, process-exit reasons) and system-crash.log
# (Android crash buffer) and bluetooth.log (Bluetooth stack lines for WristBridge peers).
set -u
# Git Bash would rewrite /sdcard/... into a Windows path.
export MSYS_NO_PATHCONV=1
OUT="${1:-evidence/watch-logs/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
SERIAL="$(adb devices | grep -v emulator | grep -m1 'device$' | cut -f1)"
if [ -z "$SERIAL" ]; then echo "No hay reloj conectado por ADB" >&2; exit 2; fi
adb -s "$SERIAL" pull /sdcard/Android/data/dev.wristbridge.watch/files/logs/. "$OUT/" >/dev/null 2>&1 && [ -n "$(ls "$OUT"/wb*.log 2>/dev/null)" ] \
  || for f in $(adb -s "$SERIAL" shell run-as dev.wristbridge.watch ls files/logs 2>/dev/null | tr -d '\r'); do
       adb -s "$SERIAL" exec-out run-as dev.wristbridge.watch cat "files/logs/$f" > "$OUT/$f"
     done
adb -s "$SERIAL" logcat -d -b crash -v threadtime > "$OUT/system-crash.log" 2>&1
adb -s "$SERIAL" logcat -d -v threadtime 2>/dev/null | grep -E "WristBridge|bt_|btm_|gatt|smp|bluetooth" > "$OUT/bluetooth.log"
echo "Registros en $OUT"; ls -l "$OUT"
