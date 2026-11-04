#!/usr/bin/env bash
# TenderOps AI - TC-OPS-04 (bash mirror of run-live-check.ps1).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BASE="http://localhost:8080"
MODEL_KEY="$(grep -E '^MODEL_API_KEY=' "$ROOT/.env" 2>/dev/null | cut -d= -f2- || true)"
if [ -z "$MODEL_KEY" ]; then
  echo "SKIP: MODEL_API_KEY is empty; LIVE mode is impossible. Rerun with a key for TC-OPS-04."
  exit 0
fi
STAMP="$(date +%s)"
REG=$(curl -fsS --max-time 60 -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"livecheck-$STAMP@tenderops.local\",\"password\":\"livecheck-123456\",\"companyName\":\"LiveCheck Co\"}")
TOKEN=$(echo "$REG" | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
AUTH=(-H "Authorization: Bearer $TOKEN")
START=$SECONDS
LOADED=$(curl -fsS --max-time 60 -X POST "$BASE/api/v1/demo/load-rfp" "${AUTH[@]}")
JOB=$(echo "$LOADED" | python3 -c "import sys,json;print(json.load(sys.stdin)['jobId'])")
VER=$(echo "$LOADED" | python3 -c "import sys,json;print(json.load(sys.stdin)['versionId'])")
for _ in $(seq 1 120); do
  STATUS=$(curl -fsS --max-time 30 "$BASE/api/v1/jobs/$JOB" "${AUTH[@]}" | python3 -c "import sys,json;print(json.load(sys.stdin)['status'])")
  [ "$STATUS" = "COMPLETED" ] && break
  [ "$STATUS" = "FAILED" ] && { echo "live-check failed: job FAILED"; exit 1; }
  sleep 5
done
MATRIX=$(curl -fsS --max-time 60 "$BASE/api/v1/tender-versions/$VER/matrix" "${AUTH[@]}")
echo "LIVE CHECK RESULT (fresh company, wall $((SECONDS - START))s)"
echo "$MATRIX" | python3 -c "
import sys, json
m = json.load(sys.stdin)
print('MUSE aiMode:', m['museMode'])
print('SAM aiMode:', m['samMode'])
print('decision:', m['decision'], 'score:', m['readinessScore'])
for r in m['rows']:
    print(' ', r['requirement']['clauseRef'], r['verdict'], r['reasonCode'])
sys.exit(0 if m['museMode'] == 'LIVE' and m['samMode'] == 'LIVE' else 1)
" && echo "TC-OPS-04: PASS"
