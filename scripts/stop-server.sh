#!/usr/bin/env bash
# TenderOps AI - stop the native server started by scripts/start-server (pid file only).
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
if ! kill -0 "$PIDV" 2>/dev/null; then
  rm -f "$PIDFILE"
  echo "pid $PIDV already gone; pid file cleared"
  exit 0
fi
NAME="$(ps -p "$PIDV" -o comm= 2>/dev/null | tr -d ' ')"
case "$NAME" in java*|*java) ;; *)
  echo "REFUSED: pid $PIDV is $NAME, not the server"
  exit 2
  ;;
esac
kill "$PIDV"
for _ in $(seq 1 30); do
  kill -0 "$PIDV" 2>/dev/null || break
  sleep 0.5
done
if kill -0 "$PIDV" 2>/dev/null; then
  kill -9 "$PIDV"
fi
rm -f "$PIDFILE"
echo "stopped pid $PIDV"
