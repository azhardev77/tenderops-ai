# TenderOps AI — Progress Tracker (resume memory)

Statuses: NOT_STARTED, IN_PROGRESS, BLOCKED, IMPLEMENTED, TESTING, PASSED, FAILED, UNVERIFIED, NOT_RUN.
Rule: continue from the first item not PASSED. Git-commit after every passing milestone (never .env, never
data/storage). After context loss: re-read MASTER_PROMPT.md + this file.

## Current position

- Step 0.1 bootstrap: PASSED (P created, MASTER_PROMPT.md saved, .gitignore/.env/.env.example, 12 scripts,
  initial commit d926134; handbook read-only recorded in REQUIREMENTS.md).
- Step A environment: PASSED (JDK 21.0.11, Maven 3.9.6, Node 24.21.0, npm 11.19.0, git 2.55.0; docker daemon
  DOWN; MODEL_API_KEY EMPTY → FALLBACK mode; evidence docs/test-logs/env-*).
- Step B docs: PASSED (20 files). Step C gate: PASSED (logged below). Next: M1 Foundation.
- M1 Foundation: PASSED (server 57/57, client build+test green). M2 Demo bootstrap: PASSED (server 69/69
  green, m2-gate3.log). M3 ingestion + visual: PASSED (server 97/97, m3-gate8.log; probe NOT_RUN,
  FALLBACK). M4 AI extraction: PASSED (server 142/142, m4-gate5.log). M5 compliance core: PASSED
  (server 271/271, m5-gate5.log; 129 new compliance tests). M6 APIs/agent/reports/E2E: PASSED
  (server 359/359, m6-gate.log; 50 new tests; TC-AGT-01/02/03, TC-RPT-01, TC-E2E-00/01 green).
  M7 client: PASSED (npm run build 0 errors; npm test 35/35 incl TC-RT-09/10/11 + TC-VIS-09
  client half; server amendment suites still green: DemoApiTest 6/6, AmendmentApiTest 4/4).
  Next: M8 infra/scripts.
- M8 infra/scripts: PASSED (server 366/366 green from clean + fat jar, m8-gate-server.log;
  client ci/build/test green 35/35; secret sweep PASS; OPS-01/02/03/05 green; TC-OPS-04
  LIVE/LIVE NO_BID 40.00 golden-exact; probe-meta-api 6/6 200; compose config UNVERIFIED
  (no Docker CLI); TC-E2E-02 NOT_RUN (deadline; browsers install OK)). Next: deadline
  steps 2-6 (LIVE check record, README, SUBMISSION, hygiene, smoke).
  Steps 2-4 done (LIVE_CONFORMANCE + README + SUBMISSION + DEMO_SCRIPT).
  Next: step 5 final commit, step 6 clean-start smoke.
- MODEL_API_KEY is PRESENT: LIVE verified (MUSE+LIVE, SAM+LIVE on golden v1; strict
  schema, SAM grammar, summary budget and tool/image capabilities probe-verified).

## Feature matrix

Columns: REQ(doc) ARCH DOCS | SRV CLI API | UNIT INT E2E NEG | SEC PERF ACC — Status

| Feature | Docs | Server | Client | Tests | Status |
|---|---|---|---|---|---|
| M1 foundation (profiles, security, utils) | PROJECT_OVERVIEW..PROGRESS_TRACKER | PASSED | PASSED | 57/57 + client smoke | PASSED |
| M2 demo bootstrap (seeder, PDFs, font) | §5 restated in REQUIREMENTS | PASSED | — | 12/12 new, 69/69 total | PASSED |
| M3 ingestion + visual | DOCUMENT_PROCESSING_PIPELINE, AI_INTEGRATION | PASSED | — | TC-ING/VIS, 97/97 total | PASSED |
| M4 AI extraction | AI_INTEGRATION, DOCUMENT_PROCESSING_PIPELINE | PASSED | — | TC-AI/QTE/GEN/SEC-extract, 142/142 total | PASSED |
| M5 compliance core | COMPLIANCE_ENGINE §14 | PASSED | — | TC-MON/FIN/CUR/PRJ/CRT/LOG/DEC/SCR/TIME/RSK/INV, 271/271 total | PASSED |
| M6 workflows/realtime/amend/agent/report | REALTIME_DESIGN, API_SPEC | PASSED | — | 50 new (company/tender/demo/matrix/decision/agent/report/dash/audit/E2E), 359/359 total | PASSED |
| M7 client | FRONTEND_ARCHITECTURE | — | PASSED | 35 Vitest (matrix/trace/chip/overlays/upload/401/banner/banned/RT-09/10/11), build clean | PASSED |
| M8 infra | DEPLOYMENT_GUIDE | PASSED | PASSED | gate 366/366 + 35/35, OPS-01..05, probe 6/6 | PASSED |
| M9 verification + self-audit | TEST_REPORT etc. | NOT_STARTED | NOT_STARTED | MUT/INV/PRB + checklist | NOT_STARTED |

## Step C doc validation gate (log)

PASSED 2026-11-04. Cross-checked all 20 docs against each other and MASTER_PROMPT.md §§1–16:
- Golden arithmetic re-verified: v1 40.00 (2/5 PASS), v2 80.00 (4 PASS + 1 UNKNOWN), v3 100.00,
  Variant B 75.00 (3 PASS + 1 FAIL), empty 0.00, TC-TIME-01 89.58, TC-DEC-03 strict >30% rule, 80/50/40
  boundaries (48/30/24 Cr, inclusive), OR-table outcomes for v2 5.1/6.1. No PROMPT_DEFECT found.
- API paths (API_SPECIFICATION) match scripts (run-live-check → /auth/login + /demo/live-check),
  REALTIME_DESIGN (SSE + ?afterSeq=N), DATABASE_DESIGN (all tables/columns used by endpoints), and
  FRONTEND_ARCHITECTURE (9 routes). Demo endpoints profile-gated consistently.
- Context-hash/fingerprint canonicalization was underspecified → fixed in COMPLIANCE_ENGINE.md
  (Asia/Kolkata deadlineDate, NULL literals, inclusive LocalDate comparisons).
- Pinned versions verified on Maven Central: spring-boot-starter-parent 3.4.3 (200 OK) and
  pdfbox 3.0.4 (200 OK). No version-substitution risk.
- Sections 2/5/6 never relaxed. Proceeding to M1 automatically per gate rule.

## Build-hygiene notes (tooling lessons, not product behavior)

- Byte-exactness: file transports are byte-exact, but the author's emission of backslash runs,
  unicode escapes and long random strings is untrustworthy (MoneyUtils RUPEE arrived as ESC[H;
  the .ps1 PNG blob arrived as a bracketed marker while rendered output misleadingly showed a full
  blob). Protocol: Java/needs-escape content uses ASCII marker tokens + python chr()-based
  replacement; every such file is asserted by docs/test-logs/verify_bytes.py (exit 0 required);
  long random strings are length/charset-guarded, never trusted from rendered output.
- Bracketed tokens (e.g. [BASE64_..._1]) may render expanded in tool output: trust ordinals/lengths.
- write_file can spuriously fail with "already exists" (os 183); retry succeeds. Verify batch file
  presence after each write batch.
- Banned grep tokens (H2/CL-04 exact strings) must never appear in authored files; MASTER_PROMPT.md
  keeps them verbatim and is excluded from the M9 grep with this documented reason.

## Milestone log

| Milestone | Result | Evidence | Commit |
|---|---|---|---|
| Bootstrap | PASSED | initial commit | d926134 |
| Step A | PASSED | docs/test-logs/env-* | (with docs commit) |
| Step B+C docs + gate | PASSED | 20 docs + this log | (docs commit) |
| M1 Foundation | PASSED | server 57/57 green (m1-gate-green.log), client build+test green | (M1 commit) |
| M2 Demo bootstrap | PASSED | server 69/69 green (m2-gate3.log, MVN_EXIT 0): 6/2/2-page PDFs, golden clauses, §5 seed, idempotent | (M2 commit) |
| M3 Ingestion + visual | PASSED | server 97/97 green (m3-gate8.log, MVN_EXIT 0): TC-ING-*, TC-VIS-01..08/11; probe NOT_RUN (no key) | (M3 commit) |
| M4 AI extraction | PASSED | server 142/142 green (m4-gate5.log, MVN_EXIT 0): TC-AI-*, TC-QTE-01, TC-GEN-01/SEC-01 extraction halves, TC-VIS-10 | (M4 commit) |
| M5 Compliance core | PASSED | server 271/271 green (m5-gate5.log, MVN_EXIT 0): TC-MON/FIN/CUR/PRJ/CRT/LOG/DEC/SCR/TIME/RSK/INV + golden engine/pipeline + GEN-01/SEC-01 verdict halves | (M5 commit) |
| M6 M6 APIs/agent/reports/E2E | PASSED | server 359/359 green (m6-gate.log, MVN_EXIT 0): TC-AGT-*/RPT-01/E2E-00/01 + company/tender/demo/matrix/decision/dashboard/audit APIs | (M6 commit) |
| M7 client (9 screens, SSE hook, 35 tests) | PASSED | npm run build clean (m7-client-build.log); npm test 35/35 (m7-client-tests3.log); DemoApiTest 6/6 + AmendmentApiTest 4/4 incl new amendment-list endpoint | (M7 commit) |
| M8 infra/scripts + AI live fixes | PASSED | mvn clean package 366/366 0 skipped 5:31 (m8-gate-server.log); npm ci/build/test green 35/35; sweep PASS; seed-demo exit 0 NO_BID 40.00; LIVE/LIVE golden-exact; probe 6/6 200; full-reset latch exit 3 + non-prod exit 0 | 0e25d94 |
| Deadline: region-FK fix + live chain + docs | PASSED | bogus sourceRegionId nulled (red→green, +1 test); gate2 367/367 5:11; golden-demo exit 0 wall 592s (v1 NO_BID 40.00, v2 CONDITIONAL_BID 80.00 LIVE/LIVE, v3 BID 100.00, APPROVED, PDF+XLSX); README/SUBMISSION/DEMO_SCRIPT/LIVE_CONFORMANCE; timeSaved 62.54-75.71% (70% not consistently met) | (final commit) |
| M9 extras (MUT/INV-500/bench/trace) | NOT_RUN | deadline mode: skipped unless time remains after steps 1-5 | |

M4 failure-fix loop: (1) SAM transport refactor reintroduced dual-constructor DI ambiguity
→ @Autowired on the 4-arg constructor. (2) Fallback operator cues broke on extraction
line-wraps inside cue phrases ("not\nless than" matched LTE "less than") → collapse
whitespace before cue matching. (3) Grounding regex missed bare "Cr" unit + trailing-dot
tokens ("2026.") → unit added, trailing dots stripped. (4) Live-double page routing regex
missed JSON-escaped quotes (page=\"3\") → optional backslash. (5) Fallback dropped foreign
currency markers (captured "100 Cr" from "USD 100 Cr") → currency prefixes in the amount
pattern, service downgrades non-INR to OTHER (R15). (6) QuoteVerifierTest expectations kept
trailing periods outside the model quote (test bug, fixed to exact spans).
M4→M5 carryovers: TC-GEN-01 verdict half (pipeline golden on Variant B), TC-SEC-01 verdict
half (injected vs clean verdict equality), TC-VIS-08 NEEDS_REVIEW routing (engine rule:
unverified/vision quotes + null confidence → NEEDS_REVIEW), tree-leaf amount conversion at
eval time, composite top-level metric OTHER + tree precedence. ALL CLOSED in M5.

M5 failure-fix loop (3 test bugs, all provably wrong vs §§5–6 ground truth, code was right):
(1) AssertJ containsExactlyInAnyOrder on List<?> does not compile (varargs capture
mismatch) → map elements to String first. (2) ConditionTreeTest orTable returned FAIL for
OR(UNKNOWN,FAIL) and OR(NR,FAIL); §6.6 precedence (PASS > NR > UNKNOWN > FAIL) gives
UNKNOWN and NEEDS_REVIEW → helper fixed. (3) GoldenPipelineTest expected BID after adding
solvency on the v1 version, but v1 keeps 5.1/6.1 FAIL (golden v3 = v2 + solvency, and v2
needs the M6 amendment merge) → test now pins 6.2 PASS, NO_BID, 60.00, hash change.
M5→M6 carryovers: pipeline-level v3 BID (needs corrigendum merge); checklist +
clarification-question generators (§6.12); run/result/risk/decision persistence;
ComplianceService.evaluate + RequirementEvaluator are the M6 seams (TC-AMD-02 spy point).
ALL CLOSED in M6: v3 BID proven in TC-E2E-01; ChecklistService + ClarificationService;
approval gate + audit; run persistence already in RunService, now served via matrix API.

M6 failure-fix loop (2 product fixes, 4 test bugs — code was right):
(1) Non-PDF upload returned 500: PdfRejectedException had no handler → mapped to 400
(product fix). (2) Agent deterministic plan ignored condition-tree leaves, so composite
5.1 (metric OTHER + tree) gathered no project evidence → plan now walks tree leaves
(product fix). (3) Test cast structuredTable to List; real shape is {"rows":[...]} →
fixed to rows + header cells. (4) Agent tools run on executor threads outside the test
transaction: @Transactional fixtures are invisible to them → agent/matrix/report/E2E
tests use own committed H2 databases + tagged PDFs. (5) AuditApiTest forgot that
register audits USER_REGISTERED → totals fixed. (6) Surefire -Dtest takes commas, not
+, and every -D arg needs quoting in this shell (mechanics).
M6→M7 carryovers: none blocking. Client halves pending: TC-VIS-09 (no validity text),
TC-RT-09/10/11 (fetch-based SSE client), TC-E2E-02 (Playwright). Full API surface per
API_SPECIFICATION.md is served; re-verify returns {jobId, runId:null} (spec updated).
M6 also committed the previously-untracked M1–M5 source files (workflow/, amendment/,
runs/, web controllers, V2 migration): HEAD did not contain the full green build.

M6 toolchain note: Maven + JDK 21 were gone at session start (only JRE 21 + JDK 8
present). Restored Maven 3.9.6 (archive.apache.org zip → C:\apache-maven-3.9.6) and
JDK 21 via the PyCharm JBR (C:\Program Files\JetBrains\PyCharm 2025.3.1.1\jbr);
baseline 309/309 green before M6 work. Set JAVA_HOME to the JBR + Maven bin on PATH
for every mvn invocation; offline flags unchanged.

M7 failure-fix loop (1 product-adjacent fix, 4 test bugs — code was right):
(1) API_SPEC documented polling as {events,lastSeq}; reality is a bare array →
client matches reality, spec corrected. Live SSE data carries payload only, so the
hook reads stage from payload.stage (verified in SseHub/WorkflowTx). (2) No RTL
auto-cleanup without vitest globals → explicit afterEach(cleanup) in setup.ts.
(3) Direct .click() does not flush React 18 state → fireEvent everywhere.
(4) A PageViewer JSDoc sentence tripped the banned-text scan → reworded.
(5) no @types/node: banned-text scan uses import.meta.glob raw (needs
src/vite-env.d.ts reference for tsc). No new npm deps; node_modules from M1 sufficed.
M7 also added server GET /tenders/{id}/amendments (UI needs the list) + 1 test.
M7→M8 carryovers: none blocking. TC-E2E-02 (Playwright) pending to M8/M9; the client
has no E2E runner yet (vitest only).

M8 failure-fix loop (first live probe ever; 3 product fixes, all red→green with new tests):
(1) Strict structured-output schema rejected: top-level `required` missed `tender`,
nested objects missed keys, bare objects missed additionalProperties → full required
arrays + nullable type-arrays + explicit tender props; conditionTree (recursive,
unsupported) now travels as a JSON-encoded string (parser also tolerates the legacy
object form: golden p4 stays green). (2) Live SAM grammar is
<|box;x1;y1;x2;y2;c;w;h|> + <|mask;x;y;data|> with a <0f> header, not the provisional
box[] grammar → parser rewritten; blank output = zero detections (still LIVE).
(3) Summary starved: default high-effort reasoning consumed all 600 max tokens
(status=incomplete) → effort low + 2000 budget; incomplete reason now surfaced in
the transport error. Also: probe-meta-api had a `$name:` parser bug + missing
-UseBasicParsing (never executed before); init-env used .NET-Core-only RNG Fill
(PS 5.1 needs Create()/GetBytes).
M8→deadline carryovers: README + SUBMISSION + DEMO_SCRIPT + LIVE record (steps 2-4),
final hygiene + smoke (steps 5-6). UNVERIFIED: docker compose config/run (no Docker
CLI/daemon). NOT_RUN: TC-E2E-02 full UI flow (browsers install OK, exit 0; no time
for the spec), M9 extras (mutations, 500-iteration invariants, long benchmarks,
deep traceability).

M3 failure-fix loop: (1) PDFBox 3 bare PDFStreamEngine registers NO state operators → vector
scans track own graphics state/CTM (else color/width/CTM silently default). (2) YDirAdj is
TOP-origin unrotated space at every rotation (bytecode + probes) → extractor flips to
bottom-left. (3) M1 tamper tests flipped the JWT's LAST char — a silent no-op when only dropped
base64 padding bits change (403 flake) → flip first signature char instead. (4) SAM test:
@DynamicPropertySource evaluates before @BeforeAll → HttpServer double in static init.
(5) python `open(f,'wb').write(open(f,'rb')...)` truncates before reading — destroyed 3 test
files, rewrote from authored content; NEVER use write+read in one expression.
M3→M4 carryovers: TC-VIS-08 requirement-routing half, TC-VIS-10 (Table 4-A zero requirements),
TC-VIS-09 client half (M7). SAM grammar stays provisional/UNVERIFIED until a live probe.

M2 failure-fix loop (all fixed, none in tests' ground truth): (1) Hibernate validate rejected
company_documents.sha256 VARCHAR mapping — V1 DDL is CHAR(64); entity fixed to CHAR(64). V1 already
defines the full M2 schema (no V2 migration needed). (2) StorageService dual constructors confused
Spring DI — @Autowired added to the primary. (3) Generator-test phrase assertions broke on layout
line-wraps — test now collapses whitespace (content check, TC-ING-03).

## Sandbox build mechanics (this session only; user scripts run plain mvn/npm)

- Shell runs as `muse-sbx-r1`; repo lives under `C:\Users\white\...`. Sandboxed java.exe CANNOT
  canonicalize (toRealPath) or ZipFS-close jars there, and cannot use sockets at all; esbuild cannot
  read parent dirs. Consequence: ALL `mvn`/`npm run build`/`npm test` commands in this session must
  run with `require_escalated` (auto-approved here), else they fail for sandbox reasons, not code reasons.
- Maven is fully offline: `-o "-Dmaven.repo.local=<P>\.m2repo"` (quotes REQUIRED for -D key=value args;
  unquoted -D is silently dropped). `.m2repo` = copy of the pre-seeded `~/.m2` cache minus broken
  `_remote.repositories` tracking, plus regenerated installed-form markers; gitignored.
- Direct Maven network is denied; the sandbox proxy port rotates per shell invocation, so no static
  proxy config is viable. Current flags are deterministic and offline.
- npm needs per-command proxy env (`npm_config_proxy/https_proxy` from `$env:HTTPS_PROXY`) plus a
  project-local cache (`--cache <P>\.npm-cache`, gitignored). package-lock.json IS committed (ci needs it).
- Dependency pins forced by offline-cache gaps (documented in pom + TECH_STACK.md): surefire 3.5.6
  (Boot's 3.5.2 lacks maven-surefire-common), JUnit 5.12.2 (cache lacks platform-launcher 1.11.4).
- Flyway V1 uses `TIMESTAMP WITH TIME ZONE` (H2 has no TIMESTAMPTZ type, even in PG mode).
- A concurrent process (likely an IDE as another user) also compiles into server/target; escalated
  `clean` wins deterministically. Evidence: docs/test-logs/m1-*.log, hello/ probes.
