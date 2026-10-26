import { expect, test } from '@playwright/test';
import { appendAccessLog, signIn } from './console';
import { deleteAllMails } from './mailpit';

test.beforeEach(async ({ page, request }) => {
  await deleteAllMails(request);
  await signIn(page, request);
});

// the buckets e2e-stats and e2e-other (e2e/controller-config.yaml) are sites only this test writes
test('shows the requests of a site from the ingress access log', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  const browser = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36';
  appendAccessLog(
    { site: 'e2e-stats', status: 200, size: 1000, uri: '/a', userAgent: browser },
    { site: 'e2e-stats', status: 200, size: 1000, uri: '/b', userAgent: browser },
    { site: 'e2e-stats', status: 200, size: 500, uri: '/c', userAgent: 'aws-sdk-java/2.30.0' },
    { site: 'e2e-stats', status: 404, size: 0, uri: '/missing', userAgent: 'curl/8.5.0' },
    // another site, not counted
    { site: 'e2e-other', status: 200, size: 10, uri: '/', userAgent: browser },
  );

  // the overview links the sites, so does the navigation
  await expect(page.getByTestId('site-e2e-stats')).toBeVisible();
  await page.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: 'Statistics' }).click();
  // the first site, in the url like every selected one
  await expect(page).toHaveURL('/logs/statistics/e2e-stats');
  await expect(page.getByLabel('Site', { exact: true })).toHaveValue('e2e-stats');
  await expect(page.getByTestId('site-title')).toHaveText('e2e-stats');
  await expect(page.getByText('stats.example.org')).toBeVisible();

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

  // every site has its own requests, the range stays when switching
  await page.getByLabel('Site', { exact: true }).selectOption('e2e-other');
  await expect(page).toHaveURL('/logs/statistics/e2e-other');
  await expect(page.getByLabel('Range')).toHaveValue('15m');
  await expect(page.getByTestId('kpi-requests')).toHaveText('1');

  // the selection survives a reload and the back button returns to the previous site
  await page.reload();
  await expect(page.getByLabel('Site', { exact: true })).toHaveValue('e2e-other');
  await page.goBack();
  await expect(page).toHaveURL('/logs/statistics/e2e-stats');
  await expect(page.getByLabel('Site', { exact: true })).toHaveValue('e2e-stats');

  expect(errors).toEqual([]);
});
