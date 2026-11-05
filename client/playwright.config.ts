import { defineConfig } from '@playwright/test';

// TC-E2E-02: Playwright UI golden flow. Run via scripts/run-e2e (it starts the
// stack, resets demo state, runs this spec, copies the video, stops everything).
export default defineConfig({
  testDir: './e2e',
  timeout: 900_000,
  expect: {
    timeout: 30_000,
  },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    video: 'on',
    trace: 'retain-on-failure',
  },
});
