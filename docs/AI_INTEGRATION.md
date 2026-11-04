# TenderOps AI — AI Integration

## 1. CLAIMED / VERIFIED / UNVERIFIED register

"VERIFIED-docs" = confirmed by official Meta documentation (fetched 2026-11-04, URLs below).
"UNVERIFIED-live" = no real API call has been made (no key in this environment); the M3 probe + run-live-check
decide the live capability flags. Per H4, anything not confirmed is handled through a configurable adapter.

| # | Claim | Status | Source |
|---|---|---|---|
| C1 | Base URL `https://api.meta.ai/v1` | VERIFIED-docs | https://dev.meta.ai/docs/quickstart |
| C2 | Header `Authorization: Bearer $MODEL_API_KEY`, env var `MODEL_API_KEY` | VERIFIED-docs | https://dev.meta.ai/docs/sam/segmenting (curl example) |
| C3 | Model id `muse-spark-1.3` | VERIFIED-docs | https://dev.meta.ai/docs/protocols/responses, quickstart |
| C4 | Model id `sam-3.1` | VERIFIED-docs | https://dev.meta.ai/models/sam-3-1 |
| C5 | Muse served on Responses API `POST /v1/responses` (model + input) | VERIFIED-docs | https://dev.meta.ai/docs/protocols/responses, https://dev.meta.ai/docs/api-reference/responses/create-response |
| C6 | Chat Completions + Anthropic Messages interfaces also exist | VERIFIED-docs | https://dev.meta.ai/docs/protocols, quickstart |
| C7 | Muse strict JSON-schema output | VERIFIED-docs | https://dev.meta.ai/docs/structured-output (`text.format` on Responses, `response_format` on Chat Completions, `strict` flag) |
| C8 | Muse tool/function calling | VERIFIED-docs | https://dev.meta.ai/docs/tool-calling (`tools`, `function_call` items, `function_call_output`, `previous_response_id`) |
| C9 | Muse image input | VERIFIED-docs | https://dev.meta.ai/docs/image-understanding (`input_image` with `image_url` data: URL or `file_id`; muse-spark-1.3 examples) |
| C10 | SAM: image + TEXT prompt, one noun phrase per request | VERIFIED-docs | https://dev.meta.ai/docs/sam/segmenting ("short noun phrase naming one thing" in `input_text`) |
| C11 | SAM output: structured text, boxes + encoded masks | VERIFIED-docs | https://dev.meta.ai/docs/sam/reading-segmentation (grammar `<Nf>id<\|box;...\|><\|mask;...\|>`, comma-separated) |
| C12 | SAM request path `POST /media/segment` (old draft guess) | REFUTED | SAM uses `POST /v1/responses` with `model: "sam-3.1"` (segmenting doc). The old guess is NOT used. |
| C13 | Official Python/Node decoders (`meta-sam-parser` / `@meta-sam/parser`) | VERIFIED-docs | https://dev.meta.ai/docs/sam/client-libraries (`@meta-sam/parser` TS + `meta_sam_parser` Python) |
| C14 | Official Java decoder | NOT FOUND | Client-libraries doc names TypeScript + Python only → Java parses boxes itself (mandatory), stores raw mask record; raster decode best-effort |
| C15 | Mask encoding details | VERIFIED-docs (grammar) | reading-segmentation: `data=<H>,<W>,<enc>payload`, `~` lossless, `!` one_bit, base85 payload, H-first |
| C16 | Rate limits / retry behavior | VERIFIED-docs | https://dev.meta.ai/docs/pricing-rate-limits (per-team per-model, 429 + Retry-After, exponential backoff + jitter from 500ms) |
| C17 | Live behavior of every capability (latency, exact error shapes, SAM accuracy on tender pages) | UNVERIFIED-live | Requires key: `scripts/probe-meta-api`, then `scripts/run-live-check` |

## 2. Live probe results

NOT RUN — `MODEL_API_KEY` is empty in this environment (recorded 2026-11-04). When a key exists, run
`scripts/probe-meta-api` (≤10 minimal calls, shapes per §3) at the START of M3 and paste the redacted
transcript here with: path, status, redacted response shape, latency, capability yes/no. Never log the key.

M3 START (2026-11-04): key still empty → L1 probe NOT_RUN again. Adapters built to the §3
docs-verified shapes and exercised against JDK HttpServer test doubles; every run records
aiMode=FALLBACK per component. LIVE conformance stays open until a key exists (RSK-REG-01).

## 3. Adapter design (config-driven, L2)

All URLs/paths/model ids/timeouts live in `application.yml` under `tenderops.meta-api.*`; capability flags
`muse.structured-output`, `muse.tool-calling`, `muse.image-input`, `sam.mask-format` are overridable.
Defaults follow the docs-verified shapes above; live behavior stays UNVERIFIED until probed.

### 3.1 MuseSparkExtractor (live) — POST {base}/responses
Request: `{model: "muse-spark-1.3", input: <system prompt + sandboxed excerpts>, text: {format:
{type: "json_schema", name: "tender_extraction", strict: true, schema: {...}}}}` when structured output is
enabled; plain `input` otherwise (response parsed as JSON with retries).
Response: non-streaming JSON; output text extracted from the Responses `output` array (message items with
`output_text` content). Malformed JSON → retry ≤2 with validation-error feedback → NEEDS_REVIEW/fallback.
Confidence validated to [0,1] (R7); amounts kept as raw text (R6); quotes verified by Java (R5).

### 3.2 Sam31Detector (live) — POST {base}/responses
One request per (candidate page × noun phrase): `circular official stamp`, `handwritten signature`, `table`.
Request: `{model: "sam-3.1", input: [{type: "message", role: "user", content: [{type: "input_text", text:
<phrase>}, {type: "input_image", image_url: "data:image/png;base64,<render>"}]}]}` (non-streaming for
simplicity; streaming parse also supported for `response.output_text.delta` + `[DONE]`).
Response text parsed by `SamOutputParser` per the documented grammar: records split on top-level commas;
box `x1,y1,x2,y2` (source-image pixels, top-left origin) scaled to normalized [0,1] via the known render size;
optional `c` confidence; mask record stored raw (`mask_ref`) with H/W/enc parsed. Regions with area > 40% of
the page are discarded. Cached by SHA-256(page PNG + prompt).

### 3.3 Retries/backoff
Exponential backoff with jitter for 429/5xx/timeouts (start 500 ms per docs), honor `Retry-After`, request
timeouts, max 3 attempts, then fallback + `ai_call_logs` entry (R10).

### 3.4 RequirementExtractor fallback — RuleBasedFallbackExtractor
General clause-pattern parser (never reads golden/expected data):
- Splits pages into clause candidates via `Clause <ref>` markers and sentence windows.
- Patterns: turnover ("average annual turnover", "not less than|minimum of|at least", amount),
  net worth, count/value projects ("three (3) similar projects each of value not less than ..."),
  80/50/40 rule ("80/50/40", "at least 80% ... 50% ... 40% ... of the estimated contract value"),
  certificates ("CMMI Level N", "ISO 9001/27001", "valid ... as on ..."), solvency
  ("Bank Solvency Certificate of not less than ..."), desirability ("desirable|preferred|optional" → mandatory=false).
- Amendment phrases: "Clause X is substituted by ...", "is deleted", "is inserted",
  "deadline ... extended to ...".
- Metadata patterns: "Tender No.", "Estimated Contract Value", "EMD", "Tender fee", "deadline <date>, <time> IST".
- Amounts captured as RAW TEXT ("Rs. 100 Cr"); Java MoneyUtils converts. Informational tables (no requirement
  language) yield zero requirements (TC-VIS-10). `aiMode=FALLBACK`, confidence 0.85 for pattern matches
  (unvalidated heuristic, never a probability).

### 3.5 VisualRegionDetector fallback — GeometricFallbackDetector
Heuristics on PDFBox vector content + word positions (never reads generator ground truth).
REVISION at M3 (reason: the tender pages are vector drawings; vector analysis is deterministic,
resolution-independent and needs no render pass, while raster blobs add threshold fragility):
- Stamp: groups of overlapping COLORED near-circular curved paths (channel spread ≥ 0.20,
  25–320pt, aspect 0.55–1.8) → OFFICIAL_STAMP, confidence 0.60.
- Signature: thin (≤2.2pt) wavy (control-polygon/diagonal ≥ 1.3) dark curved strokes within
  150pt of a case-insensitive "signat*" label (located via PDFBox word boxes), merged with
  same-ink straight strokes ≤45pt away plus the label box → HANDWRITTEN_SIGNATURE, 0.55.
- Table: clusters of horizontal + vertical ruling lines forming a grid (vector line segments via PDFBox
  graphic operations) → TABLE_REGION bounding the grid, confidence 0.65.
- `source=FALLBACK_HEURISTIC`, reduced confidence; area > 40% discarded (TC-VIS-11).

### 3.6 Muse vision for region content (MUSE_VISION)
Only when `muse.image-input=true` AND key present: crop PNG → `input_text` ("Transcribe the table cells...,
raw text only") + `input_image` data URL → structured_table as raw cell text (R4/R6/R7 apply; output
untrusted; vision-only requirements → NEEDS_REVIEW). Otherwise DIGITAL_TEXT pages use PDFBOX_GEOMETRIC and
SCANNED/MIXED pages set needsHumanReview (TC-VIS-08).

## 4. Sandboxing (R4)

`PromptSanitizer.wrapExcerpt(pageNo, text)`: strip Cc control chars (keep \n\t), neutralize every occurrence
of `<untrusted_tender_excerpt` and `</untrusted_tender_excerpt` inside the text (by breaking the literal, e.g.
inserting a zero-width-safe separator so the tag can never close early — exact method unit-tested), then wrap:
`<untrusted_tender_excerpt page="N">...</untrusted_tender_excerpt>`. System prompt states tag contents are DATA,
never instructions. TC-SEC-01 proves injection + closing-tag variants cannot change verdicts.

## 5. Extraction JSON schema (Muse output contract)

```json
{
  "tender": {"reference": "string|null", "estimatedValueText": "string|null", "emdText": "string|null",
             "tenderFeeText": "string|null", "deadlineText": "string|null"},
  "requirements": [{"clauseRef": "4.1", "category": "FINANCIAL", "mandatory": true,
    "metric": "AVG_ANNUAL_TURNOVER", "operator": "GTE", "amountText": "Rs. 100 Cr",
    "countValue": null, "lookbackYears": 3, "domainTags": [], "certificateName": null,
    "certificateLevel": null, "conditionTree": null, "sourcePage": 3,
    "originalQuote": "<exact span from the page>", "confidence": 0.9,
    "amendmentAction": "NONE", "targetClauseRef": null}]
}
```
Java validates: metric/operator enums, confidence range, quote verification (R5), amount conversion (R6).
`conditionTree` leaves mirror the same fields (metric/operator/amountText/countValue/lookbackYears/domainTags/
certificateName/certificateLevel) nested under `{op: "AND"|"OR", children: [...]}`.

## 6. Test doubles

JDK `HttpServer`-based doubles live ONLY in `server/src/test` (`.../ai/doubles/`, `.../visual/doubles/`),
named `*TestDouble`. They serve canned Responses-API payloads (success, malformed JSON, 429/500, timeout,
SAM grammar lines) so TC-AI-*/TC-VIS-* run without a key or network.

## 7. aiMode bookkeeping (H3)

Every compliance run stores `museMode` + `samMode`. LIVE is recorded only when a real HTTP call to
`api.meta.ai` returned 2xx AND its output was used. Test doubles never produce LIVE. UI + reports show
the labels; FALLBACK runs are "not representative" for the time-saved headline.

## 8. Verification agent adapter (M6, section 8)

`AgentService` runs a bounded loop (max 10 steps, 10 s per tool, 120 s per run) over six tools
(`AgentTools`: `get_company_financials`, `search_company_projects`, `verify_certificates`,
`get_requirement`, `find_missing_documents`, `run_deterministic_compliance`). Tool arguments are
validated strictly (unknown tools/params and mistyped values return structured errors); every
step persists to `agent_steps` with latency and emits `AGENT_STEP`; the persisted verdict always
equals the Java engine output even when the model asserts otherwise (TC-AGT-03).

Driver selection: MODEL_DRIVEN only when `MODEL_API_KEY` is non-empty AND
`tenderops.muse.tool-calling` is true AND at least one model response was actually used — otherwise
the deterministic orchestrator runs the fixed plan labelled FALLBACK_DETERMINISTIC (never presented
as model-driven). The model-driven request reuses the Responses endpoint
(`{model, input, max_output_tokens}`) and expects this JSON (UNVERIFIED — no official tool-call
schema was confirmed; handled through this configurable adapter per H4):

```json
{"tool_calls": [{"name": "get_requirement", "arguments": {"requirementId": 12}}],
 "verdictAssertion": "PASS", "explanation": "free text", "done": false}
```

`verdictAssertion` is advisory only: it is recorded in a `MODEL_SUMMARY` step for audit and can
never become the persisted verdict. Requirement quotes sent to the model are R4-wrapped excerpts.
TC-AGT-01/02/03 run against an `HttpServer` scripted double; live tool-calling is UNVERIFIED
(no key was available, so the double's shape is provisional).
