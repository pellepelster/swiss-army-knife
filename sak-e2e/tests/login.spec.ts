import { expect, test } from '@playwright/test';
import { clearInbox, loginLink, mailsTo } from './mailpit';

// the admin of config/local-config.yaml; the backend sends 5 links an hour to an address, so
// every run asks for one link only
const ADMIN = 'admin@localhost';

test.beforeEach(async ({ request }) => {
  await clearInbox(request);
});

test('signs in with the e-mailed link, which works once only, and signs out', async ({ page, browser, request }) => {
  // the apps need a session, the login remembers where it started from
  await page.goto('/');
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();

  await page.getByLabel('E-mail address').fill(ADMIN);
  await page.getByRole('button', { name: 'Send login link' }).click();
  await expect(page.getByTestId('login-sent')).toContainText(ADMIN);

  const link = await loginLink(request, ADMIN);
  await page.goto(link);

  // signed in, the token is gone from the address and the history
  await expect(page.getByRole('heading', { name: 'Apps' })).toBeVisible();
  expect(page.url()).not.toContain('token=');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Apps' })).toBeVisible();

  // the same link again, in another browser, is refused
  const other = await browser.newContext();
  const again = await other.newPage();
  await again.goto(link);
  await expect(again.getByRole('heading', { name: 'Sign in failed' })).toBeVisible();
  await expect(again.getByRole('alert')).toContainText('invalid, expired or was already used');
  await other.close();

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.goto('/');
  await expect(page).toHaveURL(/\/login/);
});

test('answers an address that may not sign in alike and sends it nothing', async ({ page, request }) => {
  const stranger = 'stranger@localhost';
  await page.goto('/login');
  await page.getByLabel('E-mail address').fill(stranger);
  await page.getByRole('button', { name: 'Send login link' }).click();
  await expect(page.getByTestId('login-sent')).toContainText(stranger);

  // the backend decides after it answered, give it the time a mail would take
  await page.waitForTimeout(3_000);
  expect(await mailsTo(request, stranger)).toBe(0);
});

test('refuses a link that is not one', async ({ page }) => {
  await page.goto('/signin?token=not-a-token');
  await expect(page.getByRole('heading', { name: 'Sign in failed' })).toBeVisible();
  expect(page.url()).not.toContain('token=');
});
