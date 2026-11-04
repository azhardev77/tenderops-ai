# TenderOps AI — Autonomous Tender & Compliance Processing Engine

Upload a tender PDF and watch it turn into a verified compliance matrix: SAM 3.1
finds stamps, signatures and tables; Muse Spark extracts requirements with source
page + exact quote; a deterministic Java engine (BigDecimal, no LLM verdicts)
checks each requirement against the company database and produces risks,
a missing-document checklist, a readiness score, a Bid/No-Bid recommendation and
an executive summary — all streamed live to the UI. A human must approve before
anything is final.

## Architecture (10 lines)

1. `server/` (Java 21, Spring Boot): ingestion → visual → extraction → engine → API.
2. `client/` (React 18 + Vite): 9 screens, fetch-based SSE with polling fallback.
3. PDFBox ingests and classifies pages (digital/scanned/mixed), renders 150 DPI PNGs.
4. SAM 3.1 (or a geometric fallback) detects stamp/signature/table regions.
5. Muse Spark (or a rule-based fallback) extracts requirements as strict JSON.
6. Java verifies every quote against the PDF text and evaluates every verdict.
7. Money is BigDecimal-only (scale 4); JSON carries money as strings.
8. Every pipeline event persists to workflow_events and streams over SSE (+ polling).
9. H2 (dev/test) or PostgreSQL 17 (postgres profile); Flyway owns the schema.
10. JWT auth (ADMIN/BID_MANAGER/VIEWER), company-scoped reads, insert-only audit.

## Prerequisites

JDK 21, Maven 3.9+, Node 20+, git. Optional: MODEL_API_KEY for LIVE AI mode
(without it every AI component runs in labelled FALLBACK mode), Docker Desktop
for the compose stack (see docs/DEPLOYMENT_GUIDE.md; not verified in this sandbox).

## Run (PowerShell; bash mirrors take `bash scripts/<name>.sh`)

```powershell
Test-Path .env   # if missing (first run only):
powershell -ExecutionPolicy Bypass -File scripts/init-env.ps1
powershell -ExecutionPolicy Bypass -File scripts/check-env.ps1   # MODEL_API_KEY: present|absent
mvn -B -f server/pom.xml package                                 # build (runs tests)
powershell -ExecutionPolicy Bypass -File scripts/start-server.ps1  # -> http://localhost:8080
powershell -ExecutionPolicy Bypass -File scripts/run-client.ps1    # -> http://localhost:5173
powershell -ExecutionPolicy Bypass -File scripts/run-tests.ps1     # server + client suites
powershell -ExecutionPolicy Bypass -File scripts/stop-server.ps1   # stop (pid file only)
```

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- Demo login: demo@tenderops.local — password is in `.env` (never printed).
  Without a key the demo still runs; every AI label reads FALLBACK.

## Golden demo in 6 steps (API-driven; same flow exists in the UI)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-golden-demo.ps1
```

1. Loads the bundled Smart-City RFP (tender 1, version 1) as the demo company.
2. v1 matrix: NO_BID, score 40.00 (4.1/4.2 PASS, 5.1/6.1 FAIL, 6.2 UNKNOWN).
3. Applies the bundled corrigendum (version 2): CONDITIONAL_BID, score 80.00.
4. Adds a Bank Solvency Certificate (Rs 18 Cr + document) and Re-Verifies.
5. v3 matrix: BID, score 100.00, status PENDING_HUMAN_APPROVAL.
6. Approves (APPROVED_FOR_BID) and downloads the PDF bid package + XLSX checklist.

## Docs

- docs/SUBMISSION.md — pitch, results, limitations, UNVERIFIED/NOT_RUN list.
- docs/DEMO_SCRIPT.md — click-by-click screen-recording script with expected values.
- docs/LIVE_CONFORMANCE.md — measured LIVE results (probe + golden flows).
- docs/PROGRESS_TRACKER.md — milestone resume memory and gate evidence.
- docs/API_SPECIFICATION.md, docs/COMPLIANCE_ENGINE.md, docs/AI_INTEGRATION.md.
