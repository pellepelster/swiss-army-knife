import { defineConfig, devices } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// the end-to-end tests run the console as it is deployed: served by the controller (built with
// `./gradlew :sak-controller:installDist`), the login links go to a mailpit e-mail sink in docker
export const CONSOLE_URL = 'http://localhost:18080';
export const MAILPIT_URL = 'http://localhost:18025';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env['CI'],
  reporter: 'list',
  use: {
    baseURL: CONSOLE_URL,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command:
        'docker run --rm --name sak-console-e2e-mailpit -p 127.0.0.1:18025:8025 -p 127.0.0.1:11025:1025 axllent/mailpit:v1.32.0',
      url: `${MAILPIT_URL}/api/v1/info`,
      timeout: 120_000,
      reuseExistingServer: !process.env['CI'],
      // docker passes the signal on to the container, killing the docker client would leave it running
      gracefulShutdown: { signal: 'SIGTERM', timeout: 10_000 },
    },
    {
      command: '../sak-controller/build/install/sak-controller/bin/sak-controller -config=e2e/controller-config.yaml',
      url: `${CONSOLE_URL}/`,
      timeout: 60_000,
      reuseExistingServer: !process.env['CI'],
      env: { SAK_E2E_DATA_DIR: mkdtempSync(join(tmpdir(), 'sak-console-e2e-')) },
      gracefulShutdown: { signal: 'SIGTERM', timeout: 10_000 },
    },
  ],
});
