import { defineConfig, devices } from '@playwright/test';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

// the end-to-end tests run the console as it is deployed: served by the controller (built with
// `./gradlew :sak-controller:installDist`), the login links go to a mailpit e-mail sink, the
// access log the tests write is shipped by vector to victorialogs, all three in docker
export const CONSOLE_URL = 'http://localhost:18080';
export const MAILPIT_URL = 'http://localhost:18025';
export const VICTORIALOGS_URL = 'http://127.0.0.1:19428';

// created once by the main process, the workers inherit it with the environment
process.env['SAK_E2E_ACCESS_LOG_DIR'] ??= mkdtempSync(join(tmpdir(), 'sak-console-e2e-logs-'));
export const ACCESS_LOG_DIR = process.env['SAK_E2E_ACCESS_LOG_DIR'];

// the data dir of the controller, with an object in the bucket e2e-other before it starts, the
// buckets are counted at startup
process.env['SAK_E2E_DATA_DIR'] ??= mkdtempSync(join(tmpdir(), 'sak-console-e2e-'));
const DATA_DIR = process.env['SAK_E2E_DATA_DIR'];
export const FIXTURE_OBJECT = 'hello world';
mkdirSync(join(DATA_DIR, 's3/buckets/e2e-other/data'), { recursive: true });
writeFileSync(join(DATA_DIR, 's3/buckets/e2e-other/data/fixture.txt'), FIXTURE_OBJECT);

// the images the controller role deploys
const roleDefaults = readFileSync(resolve(__dirname, '../ansible/roles/controller/defaults/main.yml'), 'utf8');
const image = (name: string) => roleDefaults.match(new RegExp(`^${name}: (\\S+)$`, 'm'))![1];
const vectorConfig = resolve(__dirname, '../ansible/roles/controller/files/vector.yaml');

// docker passes the signal on to the container, killing the docker client would leave it running
const gracefulShutdown = { signal: 'SIGTERM', timeout: 10_000 } as const;

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
      gracefulShutdown,
    },
    {
      command: `docker run --rm --name sak-console-e2e-victorialogs -p 127.0.0.1:19428:9428 ${image('controller_victorialogs_image')} -httpListenAddr=:9428 -storageDataPath=/tmp/victoria-logs-data`,
      url: `${VICTORIALOGS_URL}/health`,
      timeout: 120_000,
      reuseExistingServer: !process.env['CI'],
      gracefulShutdown,
    },
    {
      // in the network of victorialogs, where it listens on 127.0.0.1:9428 like in production
      command:
        `docker run --rm --name sak-console-e2e-vector --network container:sak-console-e2e-victorialogs ` +
        `--volume ${ACCESS_LOG_DIR}:/var/log/caddy:ro --volume ${vectorConfig}:/etc/vector/vector.yaml:ro ` +
        `--tmpfs /var/lib/vector ${image('controller_vector_image')} --config /etc/vector/vector.yaml`,
      wait: { stderr: /Vector has started/ },
      timeout: 120_000,
      reuseExistingServer: !process.env['CI'],
      gracefulShutdown,
    },
    {
      command: '../sak-controller/build/install/sak-controller/bin/sak-controller -config=e2e/controller-config.yaml',
      url: `${CONSOLE_URL}/`,
      timeout: 60_000,
      reuseExistingServer: !process.env['CI'],
      env: { SAK_E2E_DATA_DIR: DATA_DIR },
      gracefulShutdown,
    },
  ],
});
