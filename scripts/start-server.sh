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
PIDFILE="$ROOT/.run/server.pid"
if [ -f "$PIDFILE" ]; then
  OLD="$(cat "$PIDFILE")"
  if kill -0 "$OLD" 2>/dev/null; then
    echo "server already running (pid $OLD); use scripts/stop-server.sh first"
    exit 1
  fi
  rm -f "$PIDFILE"
fi
JAR="$ROOT/server/target/tenderops-server-1.0.0.jar"
[ -f "$JAR" ] || { echo "missing $JAR; run mvn package in server/ first"; exit 1; }
mkdir -p "$ROOT/.run" "$ROOT/docs/test-logs"
LOG="$ROOT/docs/test-logs/server.log"
JAVA_BIN="${JAVA_HOME:-}/bin/java"
[ -x "$JAVA_BIN" ] || JAVA_BIN="java"
nohup "$JAVA_BIN" -jar "$JAR" >"$LOG" 2>"$ROOT/docs/test-logs/server.err" &
LAUNCHED=$!
if command -v taskkill >/dev/null 2>&1; then
  WINPID="$(ps -p "$LAUNCHED" -o winpid= 2>/dev/null | tr -d ' ')"
  [ -n "$WINPID" ] && LAUNCHED="$WINPID"
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
if ! kill -0 "$LAUNCHED" 2>/dev/null; then
  echo "server process $LAUNCHED died (a squatter may hold port $PORT); see $LOG"
  exit 1
fi
echo "$LAUNCHED" > "$PIDFILE"
echo "UP pid $LAUNCHED profile $PROFILE http://localhost:$PORT (log $LOG)"
