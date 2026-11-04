#!/usr/bin/env bash
# TenderOps AI - fail-closed secret scan (called by scripts/verify-all).
# Scans tracked files + docs/ + docs/test-logs/ for the key pattern LLM_[0-9]+_
# and for the secret values in .env (MODEL_API_KEY, JWT_SECRET,
# DEMO_ADMIN_PASSWORD, PG_PASSWORD, PGADMIN_PASSWORD). Non-secret .env values
# (URLs, usernames, paths) legitimately appear in docs/compose and are NOT
# scanned. Prints ONLY PASS/FAIL, never values or file names. Exit 1 on FAIL.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
FILES="$(git ls-files 2>/dev/null || true)"
if [ -z "$FILES" ]; then
  FILES="$(find server/src client/src client/e2e scripts docs \
    README.md MASTER_PROMPT.md .env.example docker-compose.yml \
    Dockerfile.server Dockerfile.client nginx.conf \
    -type f 2>/dev/null || true)"
fi
HIT=0
while IFS= read -r f; do
  [ -f "$f" ] || continue
  if grep -aqE 'LLM_[0-9]+_' -- "$f" 2>/dev/null; then HIT=1; break; fi
done <<< "$FILES"
if [ "$HIT" -eq 0 ] && [ -f "$ROOT/.env" ]; then
  while IFS='=' read -r name value || [ -n "$name" ]; do
    name="$(echo "$name" | tr -d '[:space:]')"
    case "$name" in ''|\#*) continue;; esac
    case "$name" in
      MODEL_API_KEY|JWT_SECRET|DEMO_ADMIN_PASSWORD|PG_PASSWORD|PGADMIN_PASSWORD) ;;
      *) continue;;
    esac
    value="$(echo "$value" | tr -d '[:space:]')"
    [ "${#value}" -ge 8 ] || continue
    while IFS= read -r f; do
      [ -f "$f" ] || continue
      if grep -aqF -- "$value" "$f" 2>/dev/null; then HIT=1; break 2; fi
    done <<< "$FILES"
  done < "$ROOT/.env"
fi
if [ "$HIT" -eq 1 ]; then echo "SECRET-SWEEP: FAIL"; exit 1; else echo "SECRET-SWEEP: PASS"; fi
