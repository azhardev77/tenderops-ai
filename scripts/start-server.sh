#!/usr/bin/env bash
# TenderOps AI - run the server natively from the built fat jar (no Docker).
# Usage: scripts/start-server.sh [profile] [port]  (defaults: dev, 8080)
# .env values fill gaps only (explicit env/args win). Writes .run/server.pid,
# logs to docs/test-logs/server.log. Refuses a second instance.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ -f "$ROOT/.env" ]; then
  while IFS='=' read -r name value || [ -n "$name" ]; do
    name="$(echo "$name" | tr -d '[:space:]')"
    case "$name" in ''|\#*) continue;; esac
    if [ -z "${!name:-}" ]; then
      export "$name=$value"
    fi
  done < "$ROOT/.env"
fi
PROFILE="${1:-${SPRING_PROFILES_ACTIVE:-dev}}"
PORT="${2:-${SERVER_PORT:-8080}}"
export SPRING_PROFILES_ACTIVE="$PROFILE" SERVER_PORT="$PORT"
on_windows() { command -v taskkill >/dev/null 2>&1; }
is_alive() {
  if on_windows; then
    [ -n "$(tasklist //FI "PID eq $1" //FO CSV //NH 2>/dev/null | tr -d '\r' | grep -v '^INFO:')" ]
  else
    kill -0 "$1" 2>/dev/null
  fi
}
PIDFILE="$ROOT/.run/server.pid"
if [ -f "$PIDFILE" ]; then
  OLD="$(cat "$PIDFILE")"
  if is_alive "$OLD"; then
    echo "server already running (pid $OLD); use scripts/stop-server.sh first"
    exit 1
  fi
  rm -f "$PIDFILE"
fi
JAR="$ROOT/server/target/tenderops-server-1.0.0.jar"
[ -f "$JAR" ] || { echo "missing $JAR; run mvn package in server/ first"; exit 1; }
mkdir -p "$ROOT/.run" "$ROOT/docs/test-logs"
LOG="$ROOT/docs/test-logs/server.log"
if [ -n "${JAVA_HOME:-}" ] && command -v cygpath >/dev/null 2>&1; then
  JAVA_BIN="$(cygpath -u "$JAVA_HOME")/bin/java"
elif [ -n "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="java"
fi
nohup "$JAVA_BIN" -jar "$JAR" >"$LOG" 2>"$ROOT/docs/test-logs/server.err" &
LAUNCHED=$!
if command -v taskkill >/dev/null 2>&1; then
  LAUNCHED=""
  for _ in 1 2 3 4 5; do
    LAUNCHED="$(tr -d ' ' </proc/"$!"/winpid 2>/dev/null || true)"
    if [ -n "$LAUNCHED" ]; then break; fi
    sleep 1
  done
  if [ -z "$LAUNCHED" ]; then
    echo "could not map the server pid; see $LOG"
    exit 1
  fi
fi
for _ in $(seq 1 60); do
  if curl -fs -m 5 "http://localhost:$PORT/actuator/health" 2>/dev/null \
      | grep -q '"status":"UP"'; then
    break
  fi
  sleep 5
done
if ! curl -fs -m 5 "http://localhost:$PORT/actuator/health" 2>/dev/null \
    | grep -q '"status":"UP"'; then
  echo "server did not become healthy; see $LOG"
  exit 1
fi
if ! is_alive "$LAUNCHED"; then
  echo "server process $LAUNCHED died (a squatter may hold port $PORT); see $LOG"
  exit 1
fi
echo "$LAUNCHED" > "$PIDFILE"
echo "UP pid $LAUNCHED profile $PROFILE http://localhost:$PORT (log $LOG)"
