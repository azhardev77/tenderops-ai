# TenderOps AI — Hackathon Submission

## Pitch

Government tenders are hundred-page PDFs full of financial thresholds, project
criteria and certificates; missing one clause loses the bid. TenderOps AI turns
a tender PDF into a verified, traceable Bid/No-Bid decision in minutes: vision
models find what matters on each page, a language model extracts requirements
with exact source quotes, and a deterministic Java engine — never the AI —
decides every verdict, score and recommendation, streamed live to the reviewer
with full evidence. The human always approves; the AI never submits.

## Problem

Manual tender review is slow (our baseline assumption: 4 minutes per page),
error-prone on money math (crores/lakhs, Indian digit grouping, threshold
comparisons), and opaque (which clause produced which verdict?). Teams need
machine speed without surrendering legal-grade traceability.

## How SAM 3.1 and Muse Spark 1.3 are used (all live-verified)

- SAM 3.1 (`sam-3.1`, Responses API, one noun phrase per call: "circular
  official stamp", "handwritten signature", "table") returns bounding boxes +
  encoded masks in a `<|box;...|>`/`<|mask;...|>` grammar we verified by probe.
  Regions feed the page viewer overlays and table/stamp content extraction.
- Muse Spark (`muse-spark-1.3`) extracts requirements under a strict JSON
  schema (full `required` arrays — the API rejects anything less), writes the
  executive summary from verified facts only (low reasoning effort + a
  2000-token budget, after we measured high-effort reasoning starving a 600
  budget), and can drive the bounded agent (tool calling verified: an `add`
  function call was actually invoked; image input verified on a test PNG).
- Without a key, or on any API failure, general-algorithm fallbacks take over
  (rule-based extractor, geometric detector, template summary) and every label
  reads FALLBACK. LIVE is recorded only when a real 2xx response was used (H3).

## What is deterministic Java vs AI

- AI ONLY extracts and explains: requirement candidates, quotes, summaries.
- Java decides EVERYTHING else: quote verification (exact raw PDF span stored),
  money parsing/comparison (BigDecimal, no division-before-compare), the four
  verdicts (PASS/FAIL/UNKNOWN/NEEDS_REVIEW), condition trees, the Bid/No-Bid
  decision, the readiness score, risks, checklist and time-saved. The persisted
  agent verdict always equals the Java engine output. Approval is human-only.

## Honest results (every number measured here)

- Server: **367/367 tests green, 0 skipped**, `mvn clean package` from clean in
  5:11 (log: docs/test-logs/m8-gate2-server.log). Includes TC-INV-01..05 at
  500 seeded iterations each, TC-E2E-00/01, TC-AGT/RPT/RT/JOB/AMD suites.
- Client: `npm ci` + `npm run build` (0 errors) + **35/35 Vitest** green;
  Vite ready in 1137 ms, serves HTTP 200.
- LIVE golden v1 (demo company): MUSE=LIVE, SAM=LIVE, NO_BID 40.00, all six
  verdicts exactly per spec; grounded MUSE summary, no warning.
- LIVE fresh company: wall 221 s end-to-end, MUSE=LIVE, SAM=LIVE,
  INSUFFICIENT_DATA 0.00 (all UNKNOWN — correct for empty data). TC-OPS-04 PASS.
- LIVE chain v1→corrigendum→solvency→BID→approve→PDF/XLSX verified by
  scripts/run-golden-demo (exit 0); v2 transition CONDITIONAL_BID 80.00,
  delta +40.00 pinned by TC-E2E-01 and matched live.
- Probe latencies: models 1220 ms, muse-text 3293 ms, structured 5321 ms,
  tools 3578 ms, image 16587 ms, sam 1231 ms.
- Time-to-first-event: **2 ms** (target < 1 s). False-PASS rate: **0.00%**
  across the 22 golden verdicts asserted green (v1/v2/v3 + Variant B).
- Time-saved on golden v1 across two LIVE runs: **62.54–75.71%** (manual
  24.00 min = 6 pages × 4.0 ASSUMED min/page; assisted 5.83–8.99 min measured).
  The assisted minutes include model latency, which varies run to run, so the
  70% target was MET once and MISSED once: NOT consistently met. The baseline
  is an assumption, not a measured human benchmark, and FALLBACK runs are
  labelled not representative.

## Limitations

- No Docker here, so compose build/run is UNVERIFIED (YAML validated only).
- No passing Playwright UI flow yet: the spec + runner exist
  (client/e2e/golden-flow.spec.ts, scripts/run-e2e) but are NOT_RUN — the
  sandbox blocks listening sockets (binds EACCES); re-run scripts/run-e2e
  twice on a normal machine (see docs/TEST_REPORT.md).
- Agent MODEL_DRIVEN live run not attempted (tool channel verified, run NOT_RUN).
- `mvn`/`npm` scripts run here with sandbox-required transport flags
  (offline repo, proxy/cache); the committed scripts use plain commands and are
  written for normal machines — same goals, verified equivalent.
- Benchmarks beyond the numbers above (5 parallel uploads, JVM memory,
  per-component p50s, PRB-02 confidence bins) NOT_RUN (deadline).
- M9 extras NOT_RUN: mutation testing, deep traceability audit, TEST_REPORT.md,
  BENCHMARK_RESULTS.md, SELF_AUDIT_REPORT.md, REQUIREMENTS_TRACEABILITY.md.

## UNVERIFIED / NOT_RUN (complete list)

- UNVERIFIED: docker compose config + stack run; per-component AI p50 latencies;
  agent live run; init-env default path (refused to overwrite the real .env);
  verify-all/run-tests verbatim (sandbox network); .sh mirrors except
  start/stop/check/init/sweep (bash -n clean, logic-mirrored).
- NOT_RUN: TC-E2E-02 full UI flow (spec+runner committed unexecuted); PRB-02;
  5-parallel-upload/JVM-memory measurements; mutation sanity TC-MUT-01..08;
  M9 doc package above (except TEST_REPORT.md, which now exists but covers
  the TC-E2E-02 session only).
- data/real/ holds only .gitkeep (no PDFs): no real-world robustness run.
