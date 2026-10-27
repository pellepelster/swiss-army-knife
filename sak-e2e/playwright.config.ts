import { defineConfig, devices } from '@playwright/test';

// the local environment the tests run against: the frontend of `mise run frontend:run` (the
// login links of config/local-config.yaml lead there) and the mailpit of `mise run env:run`
export const FRONTEND_URL = process.env.SAK_E2E_FRONTEND_URL ?? 'http://localhost:4200';
export const MAILPIT_URL = process.env.SAK_E2E_MAILPIT_URL ?? 'http://localhost:8025';

// one after the other: the tests share the inbox of mailpit and the rate limits of the backend
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: FRONTEND_URL,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
