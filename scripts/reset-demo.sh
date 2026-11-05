#!/usr/bin/env bash
# TenderOps AI - reset demo state to a clean seeded stack.
# The dev profile uses an in-memory H2 database, so a server restart wipes all
# demo rows; this script also clears file storage, then starts the server fresh
# (which re-runs the seeder). Uses scripts/stop-server + scripts/start-server.
# REFUSES to run when TENDEROPS_ENV=prod or SPRING_PROFILES_ACTIVE contains prod.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi
if [ "${TENDEROPS_ENV:-}" = "prod" ] || [[ "${SPRING_PROFILES_ACTIVE:-}" == *"prod"* ]]; then
  echo "reset-demo REFUSED: TENDEROPS_ENV=prod (or a prod profile is active). This script never touches prod."
  exit 3
fi
"$ROOT/scripts/stop-server.sh"
if [ -d "$ROOT/data/storage" ]; then
  rm -f "$ROOT"/data/storage/* 2>/dev/null || true
  echo "Cleared $ROOT/data/storage"
fi
"$ROOT/scripts/start-server.sh"
echo "reset-demo complete: clean server with fresh seeded demo data."
