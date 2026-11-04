#!/usr/bin/env bash
# TenderOps AI - stop the native server started by scripts/start-server (pid file only).
# The pid file holds the Windows pid (mapped at start); either stop script works.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIDFILE="$ROOT/.run/server.pid"
[ -f "$PIDFILE" ] || { echo "server not running (no pid file)"; exit 0; }
PIDV="$(cat "$PIDFILE")"
case "$PIDV" in ''|*[!0-9]*)
  echo "bad pid file content; remove $PIDFILE manually after inspection"
  exit 2
  ;;
esac
on_windows() { command -v taskkill >/dev/null 2>&1; }
win_image() {
  tasklist //FI "PID eq $1" //FO CSV //NH 2>/dev/null | tr -d '\r' \
    | grep -v '^INFO:' | sed 's/^"//; s/",.*//'
}
is_alive() {
  if on_windows; then
    [ -n "$(win_image "$1")" ]
  else
    kill -0 "$1" 2>/dev/null
  fi
}
if ! is_alive "$PIDV"; then
  rm -f "$PIDFILE"
  echo "pid $PIDV already gone; pid file cleared"
  exit 0
fi
if on_windows; then
  IMG="$(win_image "$PIDV" | tr 'A-Z' 'a-z')"
  case "$IMG" in java.exe) ;; *)
    echo "REFUSED: pid $PIDV runs $IMG, not the server"
    exit 2
    ;;
  esac
else
  EXE="$(tr '\0' '\n' </proc/"$PIDV"/cmdline 2>/dev/null | head -1 || true)"
  BASE="${EXE##*[\\/]}"
  case "$BASE" in java|java.exe) ;; *)
    echo "REFUSED: pid $PIDV runs $BASE, not the server"
    exit 2
    ;;
  esac
fi
terminate() {
  if on_windows; then
    taskkill //PID "$1" >/dev/null 2>&1 || true
  else
    kill "$1" || true
  fi
}
force_kill() {
  if on_windows; then
    taskkill //F //PID "$1" >/dev/null 2>&1 || true
  else
    kill -9 "$1" || true
  fi
}
terminate "$PIDV"
for _ in $(seq 1 30); do
  is_alive "$PIDV" || break
  sleep 0.5
done
if is_alive "$PIDV"; then
  force_kill "$PIDV"
  sleep 2
fi
if is_alive "$PIDV"; then
  echo "FAILED to stop pid $PIDV"
  exit 1
fi
rm -f "$PIDFILE"
echo "stopped pid $PIDV"
