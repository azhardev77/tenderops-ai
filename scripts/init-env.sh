#!/usr/bin/env bash
# TenderOps AI - create .env with CSPRNG secrets (the ONLY way to create it).
# Usage: scripts/init-env.sh [outfile]  (default: .env)
# Writes MODEL_API_KEY (empty unless already exported), JWT_SECRET (48 chars),
# DEMO_ADMIN_PASSWORD (20 chars), META_API_BASE_URL. Refuses to overwrite an
# existing file. Prints key NAMES only, never values.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/.env}"
if [ -e "$OUT" ]; then
  echo "REFUSED: $OUT already exists (remove it yourself to regenerate)"
  exit 2
fi
new_secret() {
  tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"
}
{
  echo "MODEL_API_KEY=${MODEL_API_KEY:-}"
  printf 'JWT_SECRET='; new_secret 48; echo
  printf 'DEMO_ADMIN_PASSWORD='; new_secret 20; echo
  echo "META_API_BASE_URL=https://api.meta.ai/v1"
} > "$OUT"
echo "wrote $OUT with keys: MODEL_API_KEY,JWT_SECRET,DEMO_ADMIN_PASSWORD,META_API_BASE_URL"
