#!/usr/bin/env bash
# TenderOps AI - full local reset. REFUSES prod (see TC-OPS-05).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a
if [ "${TENDEROPS_ENV:-dev}" = "prod" ] || [[ "${SPRING_PROFILES_ACTIVE:-}" == *"prod"* ]]; then
  echo "full-reset REFUSED: prod environment. This script never touches prod."
  exit 3
fi
if command -v docker >/dev/null 2>&1; then
  docker compose down -v
else
  echo "docker not found: skipping compose teardown."
fi
if [ -d "$ROOT/data/storage" ]; then
  rm -rf "${ROOT:?}/data/storage"/*
  echo "Cleared $ROOT/data/storage"
fi
echo "full-reset complete (non-prod)."
