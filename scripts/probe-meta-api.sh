#!/usr/bin/env bash
# TenderOps AI - probe the Meta Model API (at most 10 minimal real calls).
# Sends the key ONLY to https://api.meta.ai/v1 (refuses any other host).
# Prints status codes only. The API key is NEVER written to logs or console output.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p "$ROOT/docs/test-logs"
stamp="$(date +%Y%m%d-%H%M%S)"
log="$ROOT/docs/test-logs/probe-meta-api-$stamp.log"
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
API_BASE="${META_API_BASE_URL:-https://api.meta.ai/v1}"
probe() { echo "$1" | tee -a "$log"; }
case "$API_BASE" in https://api.meta.ai/*) ;; *)
  probe "REFUSED: the probe sends the key only to https://api.meta.ai/v1"
  exit 2
  ;;
esac
if [ -z "${MODEL_API_KEY:-}" ]; then
  probe "MODEL_API_KEY is empty: no live probe possible. Build to the claimed shape against test doubles (FALLBACK). LIVE was not tested."
  exit 2
fi
calls=0
call() {
  name="$1"; method="$2"; url="$3"; body="${4:-}"
  if [ "$calls" -ge 10 ]; then probe "$name: SKIPPED (10-call budget exhausted)"; return; fi
  calls=$((calls + 1))
  if [ -z "$body" ]; then
    out="$(curl -s -m 30 -o /dev/null -w '%{http_code}' -X "$method" "$url" -H "Authorization: Bearer $MODEL_API_KEY" 2>/dev/null || echo CURL_ERROR)"
  else
    out="$(curl -s -m 30 -o /dev/null -w '%{http_code}' -X "$method" "$url" -H "Authorization: Bearer $MODEL_API_KEY" -H 'Content-Type: application/json' -d "$body" 2>/dev/null || echo CURL_ERROR)"
  fi
  probe "$name: status=$out"
}
probe "probe start time=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
call "models-list" "GET" "$API_BASE/models"
call "muse-text" "POST" "$API_BASE/responses" '{"model":"muse-spark-1.3","input":"Reply with the single word OK."}'
call "muse-structured" "POST" "$API_BASE/responses" '{"model":"muse-spark-1.3","input":"Return JSON.","text":{"format":{"type":"json_schema","name":"ok","strict":true,"schema":{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}}}}'
call "muse-tools" "POST" "$API_BASE/responses" '{"model":"muse-spark-1.3","input":"What is 2+2? Use the tool if available.","tools":[{"type":"function","name":"add","description":"Add two numbers","parameters":{"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"],"additionalProperties":false}}]}'
TINY_PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
call "muse-image" "POST" "$API_BASE/responses" "{\"model\":\"muse-spark-1.3\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Describe this image in one word.\"},{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,$TINY_PNG\"}]}]}"
call "sam-segment" "POST" "$API_BASE/responses" "{\"model\":\"sam-3.1\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"circular official stamp\"},{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,$TINY_PNG\"}]}]}"
probe "probe done calls=$calls (budget 10). Key never logged."
