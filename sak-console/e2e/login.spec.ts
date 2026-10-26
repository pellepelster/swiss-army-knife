import { expect, test } from '@playwright/test';
import { CONSOLE_URL } from '../playwright.config';
import { ADMIN, signOut } from './console';
import { deleteAllMails, expectNoMailTo, loginLinkFor } from './mailpit';

test.beforeEach(async ({ request }) => {
  await deleteAllMails(request);
});

test('signs in with the e-mailed link and returns to the requested page', async ({ page, request }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });

  await page.goto('/?tab=sites');
  await expect(page).toHaveURL('/login?redirect=%2F%3Ftab%3Dsites');
  // no navigation for signed out users
  await expect(page.getByRole('navigation', { name: 'Navigation' })).toHaveCount(0);

  await page.getByLabel('E-mail address').fill('Admin@example.org');
  await page.getByRole('button', { name: 'Send login link' }).click();
  await expect(page.getByTestId('login-sent')).toBeVisible();

  const link = await loginLinkFor(request, ADMIN);
  expect(link).toContain('&redirect=%2F%3Ftab%3Dsites');
  await page.goto(link);

  await expect(page).toHaveURL('/?tab=sites');
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
  await expect(page.getByTestId('personal-menu')).toContainText(ADMIN);
  expect(await page.evaluate(() => localStorage.getItem('sak.session'))).toContain(ADMIN);

  // the personal menu at the bottom of the navigation
  await page.getByTestId('personal-menu').click();
  await expect(page.getByTestId('signed-in-as')).toHaveText(ADMIN);
  await expect(page.getByRole('menu')).toContainText('Session expires');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('menu')).toHaveCount(0);

  // the session outlives the page
  await page.reload();
  await expect(page.getByTestId('personal-menu')).toBeVisible();

  // the signin page with the token is not in the history anymore
  await page.goBack();
  await expect(page).not.toHaveURL(/token=/);

  await page.goto('/');
  await signOut(page);
  await expect(page).toHaveURL('/login');
  await expect(page.getByTestId('personal-menu')).toHaveCount(0);
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
  await expect(page.getByTestId('personal-menu')).toBeVisible();

  // e.g. someone who read the link along the way
  const other = await browser.newContext({ baseURL: CONSOLE_URL });
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

  await page.goto('/');

  await expect(page).toHaveURL('/login?redirect=%2F');
  expect(await page.evaluate(() => localStorage.getItem('sak.session'))).toBeNull();
});
