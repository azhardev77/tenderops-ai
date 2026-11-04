#!/usr/bin/env bash
# TenderOps AI - key presence check. Prints ONLY present/absent, never the value.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -f "$ROOT/.env" ] && grep -qE '^MODEL_API_KEY=.+' "$ROOT/.env"; then
  echo "MODEL_API_KEY: present"
else
  echo "MODEL_API_KEY: absent"
fi
