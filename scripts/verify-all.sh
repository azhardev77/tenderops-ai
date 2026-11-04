#!/usr/bin/env bash
# TenderOps AI - full verification from clean (logs to docs/test-logs/). Exits non-zero on any failure.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p "$ROOT/docs/test-logs"
stamp="$(date +%Y%m%d-%H%M%S)"
failed=0
echo "=== server: mvn clean test ==="
serverLog="$ROOT/docs/test-logs/verify-server-$stamp.log"
if mvn -B -f "$ROOT/server/pom.xml" clean test >"$serverLog" 2>&1; then
  echo "server: passed"
else
  failed=1; echo "SERVER FAILED (see $serverLog)"; tail -40 "$serverLog"
fi
cd "$ROOT/client"
echo "=== client: npm ci ==="
ciLog="$ROOT/docs/test-logs/verify-client-ci-$stamp.log"
if npm ci --no-audit --no-fund >"$ciLog" 2>&1; then echo "npm ci: passed";
else failed=1; echo "npm ci FAILED (see $ciLog)"; tail -40 "$ciLog"; fi
echo "=== client: npm run build ==="
buildLog="$ROOT/docs/test-logs/verify-client-build-$stamp.log"
if npm run build >"$buildLog" 2>&1; then echo "build: passed";
else failed=1; echo "BUILD FAILED (see $buildLog)"; tail -40 "$buildLog"; fi
echo "=== client: npm test ==="
testLog="$ROOT/docs/test-logs/verify-client-test-$stamp.log"
if npm test >"$testLog" 2>&1; then echo "client tests: passed";
else failed=1; echo "CLIENT TESTS FAILED (see $testLog)"; tail -40 "$testLog"; fi
echo "=== secret sweep ==="
if bash "$ROOT/scripts/secret-sweep.sh"; then echo "secret sweep: passed";
else failed=1; echo "SECRET SWEEP FAILED"; fi
if [ "$failed" -ne 0 ]; then echo "VERIFY-ALL: FAILED"; exit 1; else echo "VERIFY-ALL: PASSED"; fi
