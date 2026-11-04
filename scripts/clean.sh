#!/usr/bin/env bash
# TenderOps AI - remove ONLY build outputs: server/target, client/dist, .run.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for rel in server/target client/dist .run; do
  p="$ROOT/$rel"
  case "$p" in "$ROOT"/*) ;; *)
    echo "REFUSED: $rel escapes the project root"
    exit 2
    ;;
  esac
  if [ -e "$p" ]; then
    rm -rf "$p"
    echo "removed $rel"
  else
    echo "absent $rel"
  fi
done
