#!/usr/bin/env bash
# TenderOps AI - stop the local stack (keeps data volumes).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
docker compose down
