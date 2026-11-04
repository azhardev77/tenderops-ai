# TenderOps AI — Demo Script (screen recording: ~10 min raw, cuts to 2 min)

Prereqs: server + client running (see README). Use a fresh server boot so the
demo company has no tenders yet. Timings below are wall-clock on the reference
LIVE run; the UI updates live throughout (no refresh needed). For the 2-minute
cut, keep one beat per section below (banner + score + one matrix glance).

## 0. Start (0:00)

- Server: `powershell -ExecutionPolicy Bypass -File scripts/start-server.ps1`
  → `UP ... http://localhost:8080`. API docs: /swagger-ui/index.html.
- Client: `powershell -ExecutionPolicy Bypass -File scripts/run-client.ps1`
  → http://localhost:5173 (Vite ready ~1 s).
- Open http://localhost:5173/login. Log in as demo@tenderops.local
  (password from `.env`). Land on the Dashboard (all counters zero).

## 1. Load the RFP, watch v1 go NO_BID (0:30–4:00, job ~3.5 min live)

- Nav: Tenders → Demo section → click **Load demo RFP** (button shows
  `Loading.`). Notice: tender 1 created.
- Open the tender → **Workspace** (`/tenders/1/workspace`).
- EXPECT: connection badge `STREAMING`; the stage stepper walks
  VALIDATE → PARSE → RENDER → VISUAL → EXTRACT → VERIFY → RISK → DECIDE;
  matrix rows appear one by one in the Activity Feed as verdicts compute.
- Final banner: **NO_BID**, readiness score (rule-based) **40.00**,
  aiMode badges MUSE **LIVE**, SAM **LIVE**. Time-saved varies with model
  latency (**62.54–75.71%** observed: assisted 5.83–8.99 min measured against
  24.00 assumed manual).
- Matrix rows: 4.1 PASS, 4.2 PASS, 5.1 FAIL, 6.1 FAIL, 6.2 UNKNOWN, 8.1 PASS.
- Click a source-page chip: the page viewer jumps to that page; toggle the
  stamp/signature/table overlays (source badges read SAM, not fallback).

## 2. Corrigendum → CONDITIONAL_BID (4:00–6:30, job ~2 min live)

- Open `/tenders/1/amendments` → click **Apply demo corrigendum**.
- EXPECT: diff table shows 5.1 and 6.1 MODIFIED, 0 inserted, 0 deleted;
  Decision transition `NO_BID → CONDITIONAL_BID`, Score delta `+40.00`.
- Back in the Workspace (version 2): banner **CONDITIONAL_BID**, score **80.00**:
  5.1 PASS (both projects ≥ Rs 25 Cr), 6.1 PASS via OR (ISO 9001 valid),
  6.2 still UNKNOWN. (Transition values pinned by TC-E2E-01; live run matches.)

## 3. Solvency certificate + Re-Verify → BID (6:30–7:30, engine-only, seconds)

- Nav: Company → Documents → **Upload document** (any small PDF, e.g. solvency.pdf).
- Certificates → fill Name `Bank Solvency Certificate`, Expiry `2027-03-31`,
  Amount `180000000`, select the uploaded document → save (201).
- **Re-verify tenders** → **Re-Verify latest version** (Java engine only, seconds).
- EXPECT in the Workspace: banner **BID**, score **100.00**, 6.2 PASS.

## 4. Approve + reports (7:30–8:30)

- Open `/tenders/1/package`: executive summary (MUSE, grounded, no warning),
  checklist, clarification questions.
- EXPECT: `Approval: PENDING_HUMAN_APPROVAL - readiness score (rule-based): 100.00`.
- Click **Approve for bid** → `Approval: APPROVED_FOR_BID`.
- Downloads: **Download bid package (PDF)** (~2.4 KB, starts `%PDF-`) and
  **Download checklist (XLSX)** (~5.2 KB); both open with all rows.
- Nav: Audit shows the register/upload/approve trail. Agent page (`Run agent`)
  shows the bounded tool trace with per-step latency and aiMode.

## 5. Stop (8:30)

- `powershell -ExecutionPolicy Bypass -File scripts/stop-server.ps1`
  → `stopped pid ...`. Ctrl+C the client dev server.
