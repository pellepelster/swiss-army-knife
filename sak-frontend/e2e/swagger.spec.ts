import { expect, test } from '@playwright/test';
import { signIn } from './apps';
import { deleteAllMails } from './mailpit';

const ADDRESS = 'api@example.org';

test('documents the api in swagger ui, which tries it out with the session of the apps', async ({ page, request }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await deleteAllMails(request);
  await signIn(page, request, ADDRESS);

  await page.goto('/swagger');
  // the service worker leaves swagger ui to the backend, which adds the slash
  await expect(page).toHaveURL('/swagger/');
  await expect(page.getByRole('heading', { name: /sak api/ })).toBeVisible();
  for (const path of ['/api/v1/auth/login/link', '/api/v1/static-hosting/{bucket}/access-logs/metrics']) {
    await expect(page.getByText(path, { exact: true })).toBeVisible();
  }

  // the session of the apps authorizes the requests
  await page.getByText('/api/v1/auth/who-am-i', { exact: true }).click();
  await page.getByRole('button', { name: 'Try it out' }).click();
  await page.getByRole('button', { name: 'Execute' }).click();
  const response = page.locator('.live-responses-table');
  await expect(response).toContainText('200');
  await expect(response).toContainText(ADDRESS);

  // nothing the content security policy blocked
  expect(errors).toEqual([]);
});
