# TenderOps AI — LIVE Conformance (measured 2026-11-05, key present)

LIVE per H3: a real HTTP call to api.meta.ai returned 2xx and its output was used.
Probe log: docs/test-logs/probe-meta-api-*.log. Key never logged.

## Canonical probe (scripts/probe-meta-api, 6 calls, budget 10)

| Call | Status | Latency |
|---|---|---|
| models-list | 200 | 1220 ms |
| muse-text | 200 | 3293 ms |
| muse-structured (strict json_schema) | 200 | 5321 ms |
| muse-tools | 200 | 3578 ms |
| muse-image (1px PNG) | 200 | 16587 ms |
| sam-segment (1px PNG) | 200 | 1231 ms |

models-list confirms model ids muse-spark-1.3 and sam-3.1 exist.

## Capability confirmations (2 follow-up calls, full bodies)

- Tool calling YES: response carried a function_call item (name=add,
  arguments={"a":2.0,"b":2.0}) plus a commentary message. So the agent
  MODEL_DRIVEN path has a working tool channel (agent live run itself NOT_RUN).
- Image input YES: the model answered "White" for the 1px white PNG.
- SAM on the 1px PNG returned 200 with empty output text: blank output means
  "nothing found" (parser yields zero detections, still a LIVE outcome).

## Strict-schema rules (learned from live 400s, then verified 200)

- Every object lists ALL property keys in `required` and sets
  `additionalProperties: false` (missing either is a 400).
- Nullable union types (["string","null"] etc.) are accepted under strict:true.
- Recursive schemas are unsupported: the condition tree travels as a
  JSON-encoded string (parser also tolerates the legacy object form).
- SAM grammar is `<|box;x1;y1;x2;y2;c;w;h|>` + `<|mask;x;y;data|>` with a
  `<0f>` header; mask data runs to the end of its span.
- Summary needs low reasoning effort + a 2000-token budget: default
  high-effort reasoning consumed all 600 tokens (status=incomplete,
  reason=max_output_tokens) before emitting text.

## Golden v1 LIVE (demo company, scripts/seed-demo, exit 0)

Seeded: tender 1 version 1. Matrix: MUSE=LIVE, SAM=LIVE, decision NO_BID,
score 40.00. Rows: 4.1 p3 PASS THRESHOLD_MET, 4.2 p3 PASS THRESHOLD_MET,
5.1 p4 FAIL TREE_OR, 6.1 p5 FAIL CERT_EXPIRED, 6.2 p5 UNKNOWN
NO_SOLVENCY_CERTIFICATE, 8.1 p5 PASS CERT_VALID. Summary: source=MUSE,
muse=LIVE, groundingWarning=false. Deviation from golden section 5: NONE.

## Fresh company LIVE (scripts/run-live-check, TC-OPS-04: PASS, exit 0)

Wall 221377 ms. MUSE=LIVE, SAM=LIVE, decision INSUFFICIENT_DATA, score 0.00.
All 6 rows UNKNOWN (4.1 FEWER_THAN_N_FY, 4.2 NO_RECORDS, 5.1 TREE_OR,
6.1 CERT_NOT_FOUND, 6.2 NO_SOLVENCY_CERTIFICATE, 8.1 CERT_NOT_FOUND), exactly
the section-5 empty-company expectation. Deviation: NONE.

## Not measured live

- Per-component p50 AI latencies from ai_call_logs (no API exposes them).
- Agent MODEL_DRIVEN end-to-end run (channel verified, run NOT_RUN).
- FALLBACK-vs-LIVE verdict diff on golden: both paths produce the section-5
  goldens (engine is deterministic; extraction agrees on the golden PDFs).
