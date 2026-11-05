#!/usr/bin/env bash
# TenderOps AI - run the Playwright UI golden flow (TC-E2E-02) end to end:
# loads .env into the process environment (never prints values), resets demo
# state, starts server + client, waits for health, runs Playwright, copies the
# passing run's video to docs/demo-assets/golden-flow.webm, then stops everything.
# Usage: scripts/run-e2e.sh [playwright args...]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# The JVM derives java.io.tmpdir from TMPDIR; keep temp inside the gitignored
# .run dir so restricted accounts (unwritable home temp) still work.
mkdir -p "$ROOT/.run/tmp"
export TMPDIR="$ROOT/.run/tmp"
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi
if [ -z "${DEMO_ADMIN_PASSWORD:-}" ]; then
  echo "run-e2e failed: DEMO_ADMIN_PASSWORD is empty (check .env; value never printed)"
  exit 1
fi
export E2E_DEMO_PASSWORD="$DEMO_ADMIN_PASSWORD"
"$ROOT/scripts/reset-demo.sh"
CLIENT_PID=""
cleanup() {
  if [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
    kill "$CLIENT_PID" 2>/dev/null || true
    sleep 2
    kill -9 "$CLIENT_PID" 2>/dev/null || true
  fi
  rm -f "$ROOT/.run/client.pid"
  "$ROOT/scripts/stop-server.sh" >/dev/null 2>&1 || true
  echo "run-e2e cleanup done"
}
trap cleanup EXIT
if [ ! -d "$ROOT/client/node_modules/@playwright/test" ]; then
  echo "run-e2e failed: @playwright/test is not installed; run 'npm ci' in client/ first"
  exit 1
fi
mkdir -p "$ROOT/.run" "$ROOT/docs/test-logs"
# Node tools run from the filesystem root with absolute paths: esbuild walks up
# from CWD and fails hard on an unreadable parent. Same vite server, same config
# and root as `npm run dev`; behaves identically in a normal checkout.
CLIENT_LOG="$ROOT/docs/test-logs/client-e2e.log"
(cd / && node "$ROOT/client/node_modules/vite/bin/vite.js" "$ROOT/client" --config "$ROOT/client/vite.config.ts" >"$CLIENT_LOG" 2>"$ROOT/docs/test-logs/client-e2e.err" & echo $! >"$ROOT/.run/client.pid")
CLIENT_PID="$(cat "$ROOT/.run/client.pid")"
deadline=$((SECONDS + 300))
while true; do
  if curl -sf -o /dev/null --max-time 5 "http://localhost:5173/"; then
    break
  fi
  if [ $SECONDS -gt $deadline ]; then
    echo "client did not become healthy; see $CLIENT_LOG"
    exit 1
  fi
  sleep 3
done
echo "client UP pid $CLIENT_PID http://localhost:5173 (log $CLIENT_LOG)"
if [ -d "$ROOT/.pw-browsers" ]; then
  export PLAYWRIGHT_BROWSERS_PATH="$ROOT/.pw-browsers"
fi
stamp="$(date +%Y%m%d-%H%M%S)"
E2E_LOG="$ROOT/docs/test-logs/e2e-$stamp.log"
echo "=== playwright (log $E2E_LOG) ==="
set +e
(cd / && node "$ROOT/client/node_modules/@playwright/test/cli.js" test --config "$ROOT/client/playwright.config.ts" "$@" 2>&1 | tee "$E2E_LOG"; exit "${PIPESTATUS[0]}")
CODE="$?"
set -e
if [ "$CODE" -eq 0 ]; then
  VIDEO="$(find "$ROOT/client/test-results" -name '*.webm' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n1 | cut -d' ' -f2- || true)"
  if [ -n "${VIDEO:-}" ] && [ -f "$VIDEO" ]; then
    mkdir -p "$ROOT/docs/demo-assets"
    cp -f "$VIDEO" "$ROOT/docs/demo-assets/golden-flow.webm"
    echo "video: docs/demo-assets/golden-flow.webm ($(du -m "$ROOT/docs/demo-assets/golden-flow.webm" | cut -f1) MB)"
  else
    echo "WARNING: playwright passed but no .webm found under client/test-results"
  fi
else
  echo "PLAYWRIGHT FAILED (exit $CODE; see $E2E_LOG)"
fi
exit "$CODE"
