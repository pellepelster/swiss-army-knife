import { expect, test } from '@playwright/test';
import { signIn } from './console';
import { deleteAllMails } from './mailpit';

test('redirects a domain from the dialog and stops redirecting it', async ({ page, request }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await deleteAllMails(request);
  await signIn(page, request, 'redirects@example.org');

  await page.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: 'Redirects' }).click();
  await expect(page).toHaveURL('/ingress/redirects');

  await page.getByRole('button', { name: 'Add redirect' }).click();
  const dialog = page.getByRole('dialog', { name: 'Add redirect' });
  await dialog.getByLabel('Domain', { exact: true }).fill('redirected.example.com');
  await dialog.getByLabel('External domain').first().check();
  await dialog.getByRole('textbox', { name: 'External domain' }).fill('example.org');
  await dialog.getByRole('button', { name: 'Add redirect' }).click();
  await expect(dialog).toBeHidden();

  const row = page.getByTestId('redirect-redirected.example.com');
  await expect(row.getByTestId('location')).toHaveText('https://example.org');

  await row.getByRole('button', { name: 'Delete the redirect of redirected.example.com' }).click();
  await row.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(row).toBeHidden();

  expect(errors).toEqual([]);
});
