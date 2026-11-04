#!/usr/bin/env bash
# TenderOps AI - start the local stack (postgres + server + client).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a
docker compose up --build -d
echo "Waiting for the server..."
deadline=$((SECONDS + 300))
while [ $SECONDS -lt $deadline ]; do
  if curl -fsS --max-time 5 http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
    break
  fi
  sleep 5
done
curl -fsS --max-time 5 http://localhost:8080/actuator/health | grep -q '"status":"UP"'
echo "UP: client http://localhost:3000  server http://localhost:8080  swagger http://localhost:8080/swagger-ui/index.html"
