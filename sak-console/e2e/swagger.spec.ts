import { expect, test } from '@playwright/test';
import { signIn } from './console';
import { deleteAllMails } from './mailpit';

const ADDRESS = 'api@example.org';

test('documents the api in swagger ui, which tries it out with the console session', async ({ page: console, request }) => {
  await deleteAllMails(request);
  await signIn(console, request, ADDRESS);

  // in a new tab, the console stays open
  const [page] = await Promise.all([
    console.context().waitForEvent('page'),
    console.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: 'Swagger' }).click(),
  ]);
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await expect(page).toHaveURL('/swagger/');
  await expect(console).not.toHaveURL('/swagger/');
  await expect(page.getByRole('heading', { name: /sak console api/ })).toBeVisible();
  for (const path of ['/api/v1/auth/login', '/api/v1/logs/{site}/live', '/api/v1/logs/{site}/search']) {
    await expect(page.getByText(path, { exact: true })).toBeVisible();
  }

  // the session of the console authorizes the requests
  await page.getByText('/api/v1/auth/me', { exact: true }).click();
  await page.getByRole('button', { name: 'Try it out' }).click();
  await page.getByRole('button', { name: 'Execute' }).click();
  const response = page.locator('.live-responses-table');
  await expect(response).toContainText('200');
  await expect(response).toContainText(ADDRESS);

  // nothing the content security policy blocked
  expect(errors).toEqual([]);
});
