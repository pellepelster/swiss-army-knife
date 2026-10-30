import { expect, test } from '@playwright/test';
import { APPS_URL } from '../playwright.config';
import { ADMIN, signOut } from './apps';
import { deleteAllMails, expectNoMailTo, loginLinkFor } from './mailpit';

test.beforeEach(async ({ request }) => {
  await deleteAllMails(request);
});

test('signs in with the e-mailed link and returns to the requested page', async ({ page, request }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });

  await page.goto('/redirects?tab=sites');
  await expect(page).toHaveURL('/login?redirect=%2Fredirects%3Ftab%3Dsites');

  await page.getByLabel('E-mail address').fill('Admin@example.org');
  await page.getByRole('button', { name: 'Send login link' }).click();
  await expect(page.getByTestId('login-sent')).toBeVisible();

  const link = await loginLinkFor(request, ADMIN);
  await page.goto(link);

  await expect(page).toHaveURL('/redirects?tab=sites');
  await expect(page.getByRole('heading', { name: 'Redirects' })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('sak.session'))).toContain(ADMIN);

  // the session outlives the page, the index page names the signed in address
  await page.goto('/');
  await page.reload();
  await expect(page.getByTestId('username')).toHaveText(ADMIN);

  // the signin page with the token is not in the history anymore
  await page.goBack();
  await expect(page).not.toHaveURL(/token=/);

  await signOut(page);
  await expect(page).toHaveURL('/login');
  await page.goto('/notes');
  await expect(page).toHaveURL('/login?redirect=%2Fnotes');

  // the apps of the frontend take the same session
  await page.goto('/');
  await expect(page).toHaveURL('/login?redirect=%2F');

  // nothing the content security policy blocked
  expect(errors).toEqual([]);
});

test('a link can only be used once', async ({ browser, page, request }) => {
  await page.goto('/login');
  await page.getByLabel('E-mail address').fill(ADMIN);
  await page.getByRole('button', { name: 'Send login link' }).click();
  const link = await loginLinkFor(request, ADMIN);

  await page.goto(link);
  await expect(page.getByTestId('username')).toHaveText(ADMIN);

  // e.g. someone who read the link along the way
  const other = await browser.newContext({ baseURL: APPS_URL });
  const otherPage = await other.newPage();
  await otherPage.goto(link);
  await expect(otherPage.getByRole('alert')).toContainText('invalid, expired or was already used');
  await expect(otherPage).toHaveURL('/signin');
  expect(await otherPage.evaluate(() => localStorage.getItem('sak.session'))).toBeNull();
  await other.close();
});

test('addresses that may not sign in see the same answer but get no link', async ({ page, request }) => {
  await page.goto('/login');
  await page.getByLabel('E-mail address').fill('someone@example.org');
  await page.getByRole('button', { name: 'Send login link' }).click();

  await expect(page.getByTestId('login-sent')).toBeVisible();
  await expectNoMailTo(request, 'someone@example.org');
});

test('a rejected session token leads back to the login', async ({ page }) => {
  await page.goto('/login');
  await page.evaluate(() =>
    localStorage.setItem(
      'sak.session',
      JSON.stringify({
        token: 'forged',
        email: 'admin@example.org',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }),
    ),
  );

  await page.goto('/notes');

  await expect(page).toHaveURL('/login?redirect=%2Fnotes');
  expect(await page.evaluate(() => localStorage.getItem('sak.session'))).toBeNull();
});
