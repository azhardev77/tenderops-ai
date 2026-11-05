# TenderOps AI — Test Report (TC-E2E-02 session, 2026-11-05)

Scope of THIS file: the Playwright UI golden-flow session only (TC-E2E-02).
It is not the full M9 TEST_REPORT (server/client suite totals live in the
milestone gate logs under docs/test-logs/). Every number below comes from a
command actually run in this session (H1); nothing here is projected.

## Verdict: TC-E2E-02 NOT_RUN

The Playwright spec was authored but never executed green: no browser ever
launched, so no UI assertion passed or failed. No screenshots and no video
were produced (docs/screenshots/ and docs/demo-assets/ were never created).

Reason: this sandboxed shell cannot run the stack. Binds to ANY local port
fail with EACCES (node bind test: 8080, 8081, 5173 and 49160 all EACCES), so
the Spring server (Tomcat bind 8080) and the Vite dev server cannot listen.
Separately, esbuild (used by Vite/Vitest config load) walks up from CWD and
dies with EPERM on the unreadable C:\Users\Rizwan home dir. One server start
did reach UP (pid 1584, ~04:45) but was killed by the agent during script
debugging before any test ran; the two later starts failed to bind (EACCES
reported by Spring as "Port 8080 was already in use", with no listener present
and no excluded port ranges configured). Time-box 05:45 IST was then exceeded.

## What was delivered (committed, unexecuted)

- client/e2e/golden-flow.spec.ts — full UI flow with the §13 ground-truth
  assertions (NO_BID 40.00 + 5 clause verdicts → corrigendum diff 5.1/6.1 +
  CONDITIONAL_BID/delta 40.00 → v2 80.00 → solvency cert 180000000 +
  Re-Verify → BID 100.00 + PENDING_HUMAN_APPROVAL → approve → PDF/XLSX
  non-empty + magic bytes; aiMode badges visible; "probability" /
  "chance of winning" absent). Login from E2E_DEMO_EMAIL/E2E_DEMO_PASSWORD
  process env only. All waits target real UI states (decision banners,
  matrix rows, history rows); no fixed sleeps.
- client/playwright.config.ts — baseURL http://localhost:5173, video on,
  trace retain-on-failure, 1 worker, 0 retries.
- client/e2e/fixtures/solvency.pdf — 35-byte PDF-magic fixture (same bytes
  the TC-E2E-01 API test uploads).
- scripts/reset-demo.(ps1|sh) — NEW: stop-server + clear data/storage +
  start-server (dev H2 is in-memory, so a restart is a full reset). Refuses prod.
- scripts/run-e2e.(ps1|sh) — NEW: loads .env silently, maps
  DEMO_ADMIN_PASSWORD→E2E_DEMO_PASSWORD, reset-demo, starts Vite, waits for
  :5173, runs Playwright, copies the passing run's .webm to
  docs/demo-assets/golden-flow.webm, stops client + stop-server. Node tools
  run from the drive root with absolute paths (esbuild CWD-walk guard).
- client/package.json: "e2e": "playwright test". client/vite.config.ts:
  Vitest excludes e2e/** (else `npm test` would collect the Playwright spec).
- .gitignore: client/test-results/, client/playwright-report/,
  docs/test-logs/client-e2e.err.
- Dev deps added: @playwright/test 1.64.1 (expects chromium-1248 — matches
  the pre-seeded .pw-browsers/, so no browser download is needed),
  @types/node (spec uses node:fs/path).

## Production-logic and UI changes

NONE. No server or client source file was modified for behavior; no
data-testid was added (existing testids — decision-*, matrix-row-*,
verdict-*, stage-*, activity-feed — already cover every assertion).
No bug was found in production code (the UI was never exercised). The only
client edits are test config (vite.config.ts exclude) and the npm "e2e" script.

## Checks actually run here (real results)

| # | Command | Result |
|---|---------|--------|
| 1 | tsc --noEmit (strict) on e2e/golden-flow.spec.ts + playwright.config.ts | exit 0, 0 errors |
| 2 | scripts/secret-sweep.ps1 | SECRET-SWEEP: PASS |
| 3 | node bind probe 8080/8081/5173/49160 | all EACCES (sandbox blocks listen) |
| 4 | esbuild bundle probe from client/ and C:\ | EPERM on C:\Users\Rizwan (both) |
| 5 | scripts/run-e2e.ps1 attempt A (~04:41) | server JVM crashed: account temp dir unwritable (fixed: TMP/TEMP→.run/tmp) |
| 6 | attempt B (~04:45) | server UP (pid 1584); client failed: vite has no --root flag (fixed: positional root); agent killed the run, orphaning pid 1584 |
| 7 | attempt C (~04:52) | server bind failed (sandbox EACCES); 5-min health timeout, exit 1 |
| 8 | attempt D (~04:58) | server bind failed again (EACCES); exit 1 |
| 9 | scripts/verify-all | NOT_RUN (see below) |

scripts/verify-all was NOT run in this session: `mvn clean test` needs
listening sockets (RANDOM_PORT SpringBootTest) and `npm run build`/`npm test`
need esbuild, both proven unusable in this sandbox (rows 3–4). Re-run it on a
normal machine; the suite state is unchanged from commit c86c10c (no prod edit).

## Evidence paths

- docs/test-logs/server.log (attempts A–D startup traces), docs/test-logs/server.err
- docs/test-logs/client-e2e.err (attempt-B vite --root CACError; stale, kept as evidence)
- No e2e-*.log exists (Playwright never started). No screenshots/video exist.

## Re-run (normal machine, unsandboxed shell)

powershell: `cd tenderops-ai; ./scripts/run-e2e.ps1`
bash: `cd tenderops-ai && ./scripts/run-e2e.sh`

Run it twice; TC-E2E-02 is PASSED only if the full flow passes twice.
Expected artifacts: docs/screenshots/00-login.png … 07-package-approved.png
(empty login form only — never a filled password) and
docs/demo-assets/golden-flow.webm (< 20 MB expected; if larger, git-ignore it
and report the path instead of committing).
