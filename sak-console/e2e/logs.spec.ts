import { expect, test } from '@playwright/test';
import { appendAccessLog, signIn } from './console';
import { deleteAllMails } from './mailpit';

test.beforeEach(async ({ page, request }) => {
  await deleteAllMails(request);
  await signIn(page, request);
});

test('shows the requests of a site from the ingress access log', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  const browser = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36';
  appendAccessLog(
    { site: 's3', status: 200, size: 1000, uri: '/bucket/a', userAgent: browser },
    { site: 's3', status: 200, size: 1000, uri: '/bucket/b', userAgent: browser },
    { site: 's3', status: 200, size: 500, uri: '/bucket/c', userAgent: 'aws-sdk-java/2.30.0' },
    { site: 's3', status: 404, size: 0, uri: '/bucket/missing', userAgent: 'curl/8.5.0' },
    // another site, not counted
    { site: 'unmatched', status: 0, size: 0, uri: '/.env', userAgent: 'zgrab/0.x' },
  );

  // the overview links the sites, so does the navigation
  await expect(page.getByTestId('site-s3')).toBeVisible();
  await page.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: 'Logs' }).click();
  // the first site, in the url like every selected one
  await expect(page).toHaveURL('/logs/s3');
  await expect(page.getByLabel('Site', { exact: true })).toHaveValue('s3');
  await expect(page.getByTestId('site-title')).toHaveText('s3');
  await expect(page.getByText('s3.example.org')).toBeVisible();

  // vector ships the entries within a few seconds
  await expect(async () => {
    await page.reload();
    await page.getByLabel('Range').selectOption('15m');
    await expect(page.getByTestId('kpi-requests')).toHaveText('4', { timeout: 2_000 });
  }).toPass({ timeout: 30_000 });

  await expect(page.getByTestId('kpi-error-rate')).toHaveText('25%');
  await expect(page.getByTestId('kpi-bytes')).toHaveText('2.5 kB');
  await expect(page.getByRole('img', { name: 'Requests by status' })).toBeVisible();
  await expect(page.getByRole('img', { name: 'Transferred' })).toBeVisible();
  await expect(page.getByRole('img', { name: 'Clients' })).toBeVisible();
  const agents = page.getByTestId('agent-shares');
  await expect(agents).toContainText('Chrome');
  await expect(agents).toContainText('Scripts and SDKs');

  // the unmatched requests are a site of their own, the range stays when switching
  await page.getByLabel('Site', { exact: true }).selectOption('unmatched');
  await expect(page).toHaveURL('/logs/unmatched');
  await expect(page.getByLabel('Range')).toHaveValue('15m');
  await expect(page.getByTestId('kpi-requests')).toHaveText('1');

  // the selection survives a reload and the back button returns to the previous site
  await page.reload();
  await expect(page.getByLabel('Site', { exact: true })).toHaveValue('unmatched');
  await page.goBack();
  await expect(page).toHaveURL('/logs/s3');
  await expect(page.getByLabel('Site', { exact: true })).toHaveValue('s3');

  expect(errors).toEqual([]);
});
