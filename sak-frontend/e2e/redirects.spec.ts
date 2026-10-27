import { expect, test } from '@playwright/test';
import { signIn } from './apps';
import { deleteAllMails } from './mailpit';

test('redirects a domain from its own page and stops redirecting it', async ({ page, request }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await deleteAllMails(request);
  await signIn(page, request, 'redirects@example.org', '/');

  await page.getByRole('link', { name: /Redirects/ }).click();
  await expect(page).toHaveURL('/redirects');

  await page.getByRole('link', { name: 'Create redirect' }).click();
  await expect(page).toHaveURL('/redirects/create');
  await page.getByLabel('Domain', { exact: true }).fill('redirected.example.com');
  await page.getByLabel('An external domain').check();
  await page.getByLabel('External domain', { exact: true }).fill('example.org');
  await page.getByRole('button', { name: 'Create redirect' }).click();
  await expect(page).toHaveURL('/redirects');

  const row = page.getByTestId('redirect-redirected.example.com');
  await expect(row.getByTestId('location')).toHaveText('https://example.org');

  await row.getByRole('button', { name: 'Delete the redirect of redirected.example.com' }).click();
  await row.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(row).toBeHidden();

  expect(errors).toEqual([]);
});
