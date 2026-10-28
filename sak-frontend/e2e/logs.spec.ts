import { expect, test } from '@playwright/test';
import { appendAccessLog, ensureBucket, signIn } from './apps';
import { deleteAllMails } from './mailpit';

const BROWSER = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36';

// paths no other test writes, so every run only sees its own entries
const run = Date.now().toString(36);

test.beforeEach(async ({ page, request }) => {
  await deleteAllMails(request);
  await signIn(page, request, 'logs@example.org');
  // served at its domain and at e2e-logs.example.org, its site is named like it
  await ensureBucket(page, 'e2e-logs', ['logs.example.org']);
  await ensureBucket(page, 'e2e-other', ['other.example.org']);
});

// the buckets e2e-logs and e2e-other are sites only this test writes
test('counts the requests to all the domains of a bucket in its logs tab', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });

  await page.goto('/static-hosting/e2e-logs/logs');
  await expect(page.getByTestId('logs-domains')).toContainText('logs.example.org, e2e-logs.example.org');

  appendAccessLog(
    { site: 'e2e-logs', host: 'logs.example.org', status: 200, size: 1000, uri: `/${run}/a`, userAgent: BROWSER },
    { site: 'e2e-logs', host: 'e2e-logs.example.org', status: 404, size: 0, uri: `/${run}/missing`, userAgent: 'curl/8.5.0' },
    // another bucket, not counted
    { site: 'e2e-other', host: 'other.example.org', status: 200, size: 10, uri: `/${run}/elsewhere`, userAgent: BROWSER },
  );

  // the totals of all its domains, not of the other bucket; vector ships the requests within a few
  // seconds
  await expect(async () => {
    await page.reload();
    await page.getByLabel('Range').selectOption('15m');
    await expect(page.getByTestId('logs-requests')).toHaveText('2', { timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
  await expect(page.getByTestId('logs-error-rate')).toHaveText('50%');
  await expect(page.getByRole('img', { name: 'Requests by status' })).toBeVisible();

  expect(errors).toEqual([]);
});
