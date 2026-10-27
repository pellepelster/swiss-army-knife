import { Page, expect, test } from '@playwright/test';
import { AccessLogEntry, appendAccessLog, signIn } from './console';
import { deleteAllMails } from './mailpit';

const BROWSER = 'Mozilla/5.0 (X11; Linux x86_64) Chrome/141.0 Safari/537.36';

// paths no other test writes, so every run only sees its own entries
const run = Date.now().toString(36);

const request = (site: string, path: string, status = 200): AccessLogEntry => ({
  site,
  status,
  size: 100,
  uri: `/${run}/${path}`,
  userAgent: BROWSER,
});

const row = (page: Page, path: string) =>
  page.getByRole('table', { name: 'Log entries' }).getByRole('row').filter({ hasText: `/${run}/${path}` });

test.beforeEach(async ({ page, request: api }) => {
  await deleteAllMails(api);
  await signIn(page, api, 'live@example.org');
});

test('follows the requests as they arrive, pauses and searches the stored ones', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });

  await page.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: 'Live' }).click();
  await expect(page).toHaveURL('/console/logs/live/all');
  await expect(page.getByTestId('live-state')).toHaveText('Live');
  await expect(page.getByTestId('follow-toggle')).toContainText('Pause');

  // a new request shows up with its time, site, path and status
  appendAccessLog(request('s3', 'first', 404));
  await expect(row(page, 'first')).toBeVisible({ timeout: 20_000 });
  await expect(row(page, 'first').getByRole('cell')).toHaveText([/\d{1,2}:\d\d:\d\d/, 's3', `/${run}/first`, '404']);

  // paused, nothing new shows up until it follows again
  await page.getByTestId('follow-toggle').click();
  await expect(page.getByTestId('live-state')).toHaveText('Paused');
  await expect(page.getByTestId('follow-toggle')).toContainText('Follow');
  appendAccessLog(request('s3', 'while-paused'));
  await page.waitForTimeout(4_000);
  await expect(row(page, 'while-paused')).toHaveCount(0);
  await expect(row(page, 'first')).toBeVisible();

  await page.getByTestId('follow-toggle').click();
  await expect(page.getByTestId('live-state')).toHaveText('Live');
  // the entries of the last minutes come first
  await expect(row(page, 'while-paused')).toBeVisible({ timeout: 20_000 });
  await expect(row(page, 'first')).toBeVisible();

  // a search stops following and searches all stored entries
  await page.getByRole('searchbox', { name: 'Search' }).fill(`${run}/FIRST`);
  await expect(page).toHaveURL(new RegExp(`/console/logs/live/all\\?q=${run}%2FFIRST$`));
  await expect(page.getByTestId('live-state')).toHaveText('1 found');
  await expect(row(page, 'first')).toBeVisible();
  await expect(row(page, 'while-paused')).toHaveCount(0);
  appendAccessLog(request('s3', 'while-searching'));
  await page.waitForTimeout(4_000);
  await expect(row(page, 'while-searching')).toHaveCount(0);

  // follow drops the search
  await page.getByTestId('follow-toggle').click();
  await expect(page).toHaveURL('/console/logs/live/all');
  await expect(page.getByRole('searchbox', { name: 'Search' })).toHaveValue('');
  await expect(page.getByTestId('live-state')).toHaveText('Live');
  await expect(row(page, 'while-searching')).toBeVisible({ timeout: 20_000 });

  expect(errors).toEqual([]);
});

test('follows one site only', async ({ page }) => {
  await page.goto('/console/logs/live');
  await page.getByLabel('Site', { exact: true }).selectOption('s3');
  await expect(page).toHaveURL('/console/logs/live/s3');
  await expect(page.getByTestId('live-state')).toHaveText('Live');

  appendAccessLog(request('unmatched', 'elsewhere', 0), request('s3', 'here'));

  await expect(row(page, 'here')).toBeVisible({ timeout: 20_000 });
  await expect(row(page, 'elsewhere')).toHaveCount(0);

  // the rows come in pages of the selected size, the size is remembered
  await expect(page.getByLabel('Rows')).toHaveValue('50');
  await page.getByLabel('Rows').selectOption('25');
  await expect(page.getByTestId('live-range')).toHaveText(/1–\d+ of \d+/);
  await expect(page.getByRole('navigation', { name: 'Pages' }).getByRole('button', { name: '1' })).toHaveAttribute(
    'aria-current',
    'page',
  );

  // the site and the search survive a reload
  await page.getByRole('searchbox', { name: 'Search' }).fill(`${run}/here`);
  await expect(page.getByTestId('live-state')).toHaveText('1 found');
  await page.reload();
  await expect(page.getByLabel('Rows')).toHaveValue('25');
  await expect(page.getByLabel('Site', { exact: true })).toHaveValue('s3');
  await expect(page.getByRole('searchbox', { name: 'Search' })).toHaveValue(`${run}/here`);
  await expect(page.getByTestId('live-state')).toHaveText('1 found');
});
