#!/usr/bin/env bash
# TenderOps AI - seed the demo flow on a running stack.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BASE="http://localhost:8080"
DEMO_PWD="$(grep -E '^DEMO_ADMIN_PASSWORD=' "$ROOT/.env" 2>/dev/null | cut -d= -f2- || true)"
if [ -z "$DEMO_PWD" ]; then echo "seed-demo failed: DEMO_ADMIN_PASSWORD is empty in .env"; exit 1; fi
LOGIN=$(curl -fsS --max-time 30 -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"demo@tenderops.local\",\"password\":\"$DEMO_PWD\"}")
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
AUTH=(-H "Authorization: Bearer $TOKEN")
LOADED=$(curl -fsS --max-time 60 -X POST "$BASE/api/v1/demo/load-rfp" "${AUTH[@]}")
echo "$LOADED" | python3 -c "import sys,json;d=json.load(sys.stdin);print('tender',d['tenderId'],'version',d['versionId'],'duplicate',d['duplicate'])"
