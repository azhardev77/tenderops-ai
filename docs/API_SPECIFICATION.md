# TenderOps AI — API Specification

Base URL: `http://localhost:8080/api/v1`. Auth: `Authorization: Bearer <JWT>` except register/login.
All money fields serialize as STRINGS (scale 4). Errors: RFC 7807 ProblemDetail, no stack traces.
All tender-scoped reads are restricted to the caller's companyId (TC-SEC-02).

## Conventions

- Dates: ISO-8601. Deadlines carry `+05:30` offset.
- Pagination: `?page=0&size=20` where lists can grow (tenders, audit logs).
- Idempotency: duplicate upload (same SHA-256, same company) returns the existing record with
  `duplicate: true` and does not reprocess (TC-ING-02).

## Auth

### POST /auth/register
Body: `{email, password, companyName}`. Creates user (BID_MANAGER) + NEW EMPTY company.
201 → `{accessToken, tokenType: "Bearer", expiresIn, user: {id, email, role, companyId}}`.

### POST /auth/login
Body: `{email, password}`. 200 → same shape as register. Wrong credentials → 401 ProblemDetail.

## Dashboard

### GET /dashboard/stats
Real aggregates for the caller's company:
`{totalTenders, processingTenders, completedTenders, decisionBreakdown: {BID, CONDITIONAL_BID, NO_BID,
INSUFFICIENT_DATA}, pendingReviews, upcomingDeadlines: [{tenderId, reference, deadline}], criticalIssues,
processingPerformance: {avgMsPerPage, lastRunMs}, timeSaved: {percent, manualMinutes, assistedMinutes,
aiMode, representative: boolean}}`.

### GET /dashboard/events
SSE stream (company scope): JOB_*, DECISION_UPDATED. Same SSE rules as §7 (Last-Event-ID replay via
`?afterSeq=N` JSON equivalent `GET /dashboard/events?afterSeq=N`).

## Tenders & versions

### POST /tenders (multipart: file)
Upload a tender PDF. Starts a workflow job.
201 → `{tenderId, versionId, jobId, duplicate: false}` (or existing ids + `duplicate: true`).

### POST /demo/load-rfp (dev/demo/test profiles only)
Loads the bundled `Smart_City_RFP_v1.pdf` for the caller's company. Same response as upload.

### GET /tenders?page=&size=
`{items: [{id, title, reference, latestVersion, latestDecision, updatedAt}], page, size, total}`.

### GET /tenders/{id}
Tender + versions + latest run summary.

### GET /tender-versions/{id}
Version metadata incl. reference, estimatedValue (string), emdAmount, tenderFee, submissionDeadline.

### GET /tender-versions/{id}/pages
`[{pageNo, classification, needsHumanReview, reviewReason}]`.

### GET /tender-versions/{id}/pages/{n}/image
`image/png` render.

### GET /tender-versions/{id}/pages/{n}/text
`{pageNo, text}` (extracted, position-sorted).

### GET /tender-versions/{id}/regions?page=N
`[{id, pageNo, regionType, bbox: {x0,y0,x1,y1}, confidence, source, contentSource, regionText,
structuredTable, cropUrl}]`. `cropUrl` = `/api/v1/regions/{id}/crop`.

### GET /regions/{id}/crop
`image/png` crop (+2% padding).

## Requirements, matrix, re-verify

### GET /tender-versions/{id}/requirements
Strict schema per §6.2: `[{id, clauseRef, category, mandatory, metric, operator, amountText, amountValue
(string|null), countValue, lookbackYears, domainTags[], certificateName, certificateLevel, conditionTree,
sourceDocument, sourcePage, sourceRegionId, originalQuote, quoteVerified, confidence}]`.
For corrigendum versions: `amendmentAction`, `targetClauseRef`.

### GET /tender-versions/{id}/matrix?runId= (default latest)
`{runId, museMode, samMode, readinessScore: "40.00", decision, rows: [{requirement, verdict, reasonCode,
calculationTrace: {formula, operands: {string:string}, threshold, comparison, verdict, ruleVersion,
evidenceIds[], provenance, contextHash}, copied}]}`.

### POST /tender-versions/{id}/re-verify
Re-runs ONLY the Java engine on current requirements (never re-calls AI). 202 →
`{jobId, runId: null}` — the run id does not exist yet; poll `GET /jobs/{jobId}` until
`COMPLETED`, then read the new run via `GET /tender-versions/{id}/matrix` (latest).

## Risks, checklist, clarifications, summary

### GET /tender-versions/{id}/risks?runId=
`[{severity, riskCode, sourceClause, evidenceSummary, detectionReason, businessImpact, correctiveAction}]`.

### GET /tender-versions/{id}/checklist?runId=
Missing-document checklist: `[{clauseRef, requirement, missingItem, correctiveAction}]` (every UNKNOWN).

### GET /tender-versions/{id}/clarifications?runId=
`[{clauseRef, question}]` (every NEEDS_REVIEW/ambiguous clause).

### GET /tender-versions/{id}/summary?runId=
`{text, groundingWarning, source: "MUSE" | "FALLBACK_TEMPLATE", museMode}`.

## Decisions & approval

### GET /tender-versions/{id}/decision?runId=
`{recommendation, approvalStatus, readinessScore, warnings[], decidedAt}`.

### POST /tender-versions/{id}/decision/approve (ADMIN/BID_MANAGER)
Body: `{decision: "APPROVED_FOR_BID" | "REJECTED_NO_BID"}`. Audit-logged. 200 → updated decision.

## Amendments

### POST /tenders/{id}/amendments (multipart: file, kind=CORRIGENDUM|FULL_REVISION)
Uploads an amendment, runs the pipeline for the new version, diffs + selective re-verifies.
201 → `{versionId, jobId, amendmentId}`.

### POST /demo/load-corrigendum?tenderId= (dev/demo/test only)
One-click demo corrigendum (`Corrigendum_1.pdf`). Same response.

### GET /amendments/{id}/diff
`{inserted: [clauseRef], deleted: [], modified: ["5.1","6.1"], thresholdChanges, operatorChanges,
conditionTreeChanges, deadlineChanged: false, prevDecision, newDecision, scoreDelta: "40.00"}`.

## Company database

- `GET /company` → profile incl. `dataVersion`.
- `PUT /company` → update profile (bumps dataVersion).
- `GET/POST /company/financials`, `PUT/DELETE /company/financials/{id}` — money as strings.
- `GET/POST /company/projects`, `PUT/DELETE /company/projects/{id}`.
- `GET/POST /company/certificates`, `PUT/DELETE /company/certificates/{id}` (solvency = name SOLVENCY).
- `POST /company/documents` (multipart) → `{id, fileName, sha256}`; link via `documentId` fields.
Every write bumps `dataVersion` and is audit-logged with before/after.

## Agent

### POST /tender-versions/{id}/agent/run
Body: `{requirementId?}`. Runs the bounded tool loop (or deterministic orchestrator fallback).
202 → `{agentRunId}`.

### GET /agent-runs/{id}
`{id, mode: "MODEL_DRIVEN" | "FALLBACK_DETERMINISTIC", museMode, finalVerdict, steps: [{stepNo, toolName,
input, output, latencyMs, error}]}`.

## Reports

- `GET /tender-versions/{id}/reports/bid-package.pdf` → `application/pdf` (starts with `%PDF-`, ≥2 pages).
- `GET /tender-versions/{id}/reports/checklist.xlsx` → OOXML workbook (matrix + checklist sheets).

## Jobs & events

### GET /jobs/{id}
`{id, versionId, status, currentStage, progress: {stage: {status, percent, error}}, attempt, error}`.

### GET /tender-versions/{id}/events (SSE)
`text/event-stream`; `id:` = seq; `event:` = type; `data:` = JSON payload. Honors `Last-Event-ID`
(replays seq > id). Heartbeat comment every 15 s. Requires Bearer token (client uses fetch reader).
Polling equivalent: `GET /tender-versions/{id}/events?afterSeq=N` → `{events: [{seq, type, stage, payload,
createdAt}], lastSeq}`.

## Admin & audit

- `GET /users` (ADMIN) → users of the caller's company.
- `PATCH /users/{id}/role` (ADMIN) → `{role}`.
- `GET /audit-logs?entityType=&page=&size=` → `{items: [{action, entityType, entityId, before, after,
userId, createdAt}]}`.

## Demo / test support (dev/demo/test profiles only)

- `POST /demo/live-check` (ADMIN): runs the golden flow end-to-end and returns
  `{museMode, samMode, decision, readinessScore, verdicts: {clauseRef: verdict}}`. Used by run-live-check.
- `GET /demo/sample-pdfs` → lists bundled sample PDFs.

## OpenAPI

`GET /v3/api-docs`, Swagger UI at `/swagger-ui.html` (dev only exposure documented in SECURITY_AND_PRIVACY.md).
