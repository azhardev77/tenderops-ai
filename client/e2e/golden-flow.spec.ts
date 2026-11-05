import { expect, test, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';

// TC-E2E-02: Playwright UI golden flow (mirrors the TC-E2E-01 API flow).
// Login comes from process env only (scripts/run-e2e maps .env -> E2E_*
// without printing values). Never hardcode or log credentials here.
const EMAIL = process.env['E2E_DEMO_EMAIL'] ?? 'demo@tenderops.local';
const PASSWORD = process.env['E2E_DEMO_PASSWORD'] ?? '';
const SHOT_DIR = path.resolve(__dirname, '../../docs/screenshots');
const SOLVENCY_FIXTURE = path.resolve(__dirname, 'fixtures/solvency.pdf');

// Async pipeline stages (ingest pipeline, amendment merge, re-verify) finish on
// their own schedule; every wait below targets a real UI state, never a sleep.
const JOB_TIMEOUT_MS = 300_000;
const POLL_INTERVALS_MS = [2_000, 3_000, 5_000, 5_000, 10_000, 10_000];

async function snap(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: path.join(SHOT_DIR, name), fullPage: true });
}

/** H5: no win-probability language anywhere in the visible UI text. */
async function expectCleanText(page: Page): Promise<void> {
  const text = await page.evaluate(() => document.body.innerText);
  expect(text.toLowerCase()).not.toContain('probability');
  expect(text.toLowerCase()).not.toContain('chance of winning');
}

async function expectMatrixRow(
  page: Page,
  clause: string,
  verdict: string,
): Promise<void> {
  const row = page.getByTestId(`matrix-row-${clause}`);
  await expect(row).toBeVisible();
  await expect(row.getByTestId(`verdict-${verdict}`)).toBeVisible();
}

test.describe.configure({ mode: 'serial' });

test('golden flow: login, demo RFP, corrigendum, solvency, approve, downloads', async ({
  page,
}) => {
  test.setTimeout(600_000);
  expect(PASSWORD, 'E2E_DEMO_PASSWORD must be set (run via scripts/run-e2e)').not.toBe(
    '',
  );
  fs.mkdirSync(SHOT_DIR, { recursive: true });

  // 1. Login as the seeded demo user. The screenshot is taken BEFORE typing,
  // so no password is ever captured.
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: /login/i })).toBeVisible();
  await snap(page, '00-login.png');
  await page.getByLabel(/email/i).fill(EMAIL);
  await page.getByLabel(/password/i).fill(PASSWORD);
  await page.getByRole('button', { name: 'Login' }).click();
  await page.waitForURL('**/', { timeout: 30_000 });
  await expect(page.getByRole('link', { name: 'Tenders' })).toBeVisible();
  await expect(page.getByText(EMAIL)).toBeVisible();
  await expectCleanText(page);
  await snap(page, '01-dashboard.png');

  // 2. Load the demo RFP, wait for the pipeline to finish (live UI update).
  await page.getByRole('link', { name: 'Tenders' }).click();
  await page.getByRole('button', { name: 'Load demo RFP' }).click();
  await page.waitForURL('**/tenders/*/workspace', { timeout: 60_000 });
  const tenderId = page.url().match(/\/tenders\/(\d+)\/workspace/)?.[1];
  expect(tenderId, 'workspace URL carries the tender id').toBeTruthy();

  await expect(page.getByTestId('decision-NO_BID')).toBeVisible({
    timeout: JOB_TIMEOUT_MS,
  });
  await expect(
    page.locator('p', { hasText: 'Readiness score (rule-based):' }),
  ).toContainText('40.00');
  await expectMatrixRow(page, '4.1', 'PASS');
  await expectMatrixRow(page, '4.2', 'PASS');
  await expectMatrixRow(page, '5.1', 'FAIL');
  await expectMatrixRow(page, '6.1', 'FAIL');
  await expectMatrixRow(page, '6.2', 'UNKNOWN');
  await expect(page.getByText(/MUSE\/(LIVE|FALLBACK)/)).toBeVisible();
  await expect(page.getByText(/SAM\/(LIVE|FALLBACK)/)).toBeVisible();
  await expectCleanText(page);
  await snap(page, '02-workspace-v1-no-bid.png');

  // 3. Amendment tab: apply the demo corrigendum, wait for the merge to finish.
  await page.getByRole('link', { name: 'Amendments' }).click();
  await page.getByRole('button', { name: 'Apply demo corrigendum' }).click();
  await expect(
    page.getByText('Amendment accepted; the merge pipeline is running.'),
  ).toBeVisible();
  await expect(async () => {
    await page.reload();
    await expect(
      page.locator('tbody tr', { hasText: 'CONDITIONAL_BID' }).first(),
    ).toBeVisible({ timeout: 15_000 });
  }).toPass({ timeout: JOB_TIMEOUT_MS, intervals: POLL_INTERVALS_MS });
  await page
    .locator('tbody tr', { hasText: 'CONDITIONAL_BID' })
    .first()
    .getByRole('button', { name: 'Show diff' })
    .click();
  const diff = page.locator('section', {
    has: page.getByRole('heading', { name: /Diff for amendment/ }),
  });
  await expect(diff.getByTestId('decision-CONDITIONAL_BID')).toBeVisible();
  await expect(diff.getByRole('cell', { name: '5.1' })).toBeVisible();
  await expect(diff.getByRole('cell', { name: '6.1' })).toBeVisible();
  await expect(diff).toContainText('delta 40.00');
  await expectCleanText(page);
  await snap(page, '03-amendment-diff.png');

  // 4. Workspace v2 shows CONDITIONAL_BID at readiness 80.00.
  await page.goto(`/tenders/${tenderId}/workspace`);
  await expect(page.getByTestId('decision-CONDITIONAL_BID')).toBeVisible({
    timeout: 60_000,
  });
  await expect(
    page.locator('p', { hasText: 'Readiness score (rule-based):' }),
  ).toContainText('80.00');
  await expectCleanText(page);
  await snap(page, '04-workspace-v2-conditional.png');

  // 5. Company database: upload the solvency PDF, read its id back from the UI,
  // add the 18-crore Bank Solvency Certificate.
  await page.getByRole('link', { name: 'Company' }).click();
  const docs = page.locator('section', {
    has: page.getByRole('heading', { name: 'Supporting documents' }),
  });
  await docs.getByLabel(/Document \(PDF, PNG, JPEG\)/).setInputFiles(SOLVENCY_FIXTURE);
  await docs.getByRole('button', { name: 'Upload document' }).click();
  const solvencyRow = docs.locator('li', { hasText: 'solvency.pdf' });
  await expect(solvencyRow).toBeVisible();
  const docId = (await solvencyRow.innerText()).match(/#(\d+)/)?.[1];
  expect(docId, 'uploaded document id is shown in the list').toBeTruthy();

  const certs = page.locator('section', {
    has: page.getByRole('heading', { name: 'Certificates' }),
  });
  await certs.getByLabel('Name').fill('Bank Solvency Certificate');
  await certs.getByLabel('Expiry date').fill('2027-03-31');
  await certs.getByLabel('Amount (string)').fill('180000000');
  await certs.getByLabel('Document id').fill(docId ?? '');
  await certs.getByRole('button', { name: 'Add certificate' }).click();
  await expect(
    certs.locator('tbody tr', { hasText: 'Bank Solvency Certificate' }),
  ).toBeVisible();
  await expectCleanText(page);
  await snap(page, '05-company-solvency.png');

  // 6. Re-Verify -> BID at readiness 100.00.
  const reverify = page.locator('section', {
    has: page.getByRole('heading', { name: 'Re-verify tenders' }),
  });
  await reverify.getByRole('button', { name: 'Re-Verify latest version' }).click();
  await expect(reverify.getByText(/job \d+ running/)).toBeVisible();
  await page.goto(`/tenders/${tenderId}/workspace`);
  await expect(async () => {
    await page.reload();
    await expect(page.getByTestId('decision-BID')).toBeVisible({ timeout: 15_000 });
  }).toPass({ timeout: JOB_TIMEOUT_MS, intervals: POLL_INTERVALS_MS });
  await expect(
    page.locator('p', { hasText: 'Readiness score (rule-based):' }),
  ).toContainText('100.00');
  await expectCleanText(page);
  await snap(page, '06-workspace-v2-bid.png');

  // 7. Bid package: pending human approval -> approve -> downloads non-empty.
  await page.goto(`/tenders/${tenderId}/package`);
  await expect(page.getByTestId('decision-BID')).toBeVisible({ timeout: 60_000 });
  await expect(
    page.locator('p', { hasText: 'readiness score' }),
  ).toContainText('100.00');
  await expect(page.getByText('PENDING_HUMAN_APPROVAL')).toBeVisible();
  await page.getByRole('button', { name: 'Approve for bid' }).click();
  await expect(page.getByText('APPROVED_FOR_BID')).toBeVisible();
  await expectCleanText(page);
  await snap(page, '07-package-approved.png');

  const downloadDir = test.info().outputPath('downloads');
  fs.mkdirSync(downloadDir, { recursive: true });
  const pdfWait = page.waitForEvent('download', { timeout: 60_000 });
  await page.getByRole('button', { name: 'Download bid package (PDF)' }).click();
  const pdfDownload = await pdfWait;
  const pdfPath = path.join(downloadDir, 'bid-package.pdf');
  await pdfDownload.saveAs(pdfPath);
  const pdfBytes = fs.readFileSync(pdfPath);
  expect(pdfBytes.length).toBeGreaterThan(0);
  expect(pdfBytes.subarray(0, 5).toString('utf8')).toBe('%PDF-');

  const xlsxWait = page.waitForEvent('download', { timeout: 60_000 });
  await page.getByRole('button', { name: 'Download checklist (XLSX)' }).click();
  const xlsxDownload = await xlsxWait;
  const xlsxPath = path.join(downloadDir, 'checklist.xlsx');
  await xlsxDownload.saveAs(xlsxPath);
  const xlsxBytes = fs.readFileSync(xlsxPath);
  expect(xlsxBytes.length).toBeGreaterThan(0);
  expect(xlsxBytes.subarray(0, 2).toString('utf8')).toBe('PK');
});
