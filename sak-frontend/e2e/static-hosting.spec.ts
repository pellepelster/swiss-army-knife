import { expect, test } from '@playwright/test';
import { FIXTURE_OBJECT } from '../playwright.config';
import { putObject, signIn } from './apps';
import { deleteAllMails } from './mailpit';

test.use({ permissions: ['clipboard-read', 'clipboard-write'] });

test('creates a bucket, lists it with its objects and size, edits it and shows its credentials', async ({
  page,
  request,
}) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await deleteAllMails(request);
  await signIn(page, request, 'buckets@example.org', '/');

  await page.getByRole('link', { name: /Static hosting/ }).click();
  await expect(page).toHaveURL('/static-hosting');

  await page.getByRole('link', { name: 'Create static hosting', exact: true }).click();
  await expect(page).toHaveURL('/static-hosting/create');
  await page.getByLabel('Name').fill('e2e-hosting');
  await page.getByRole('button', { name: 'Add domain' }).click();
  await page.getByLabel('Domain 1', { exact: true }).fill('hosting.example.org');
  await page.getByRole('button', { name: 'Create static hosting', exact: true }).click();
  await expect(page).toHaveURL('/static-hosting/e2e-hosting/configuration');
  await expect(page.getByTestId('objects')).toHaveText('0', { timeout: 20_000 });
  // its domain and its subdomain of the root domain
  await expect(page.getByTestId('domains').getByRole('listitem')).toHaveText([
    'https://hosting.example.org/',
    'https://e2e-hosting.example.org/',
  ]);

  // the domains change on the edit page, the name can not
  await page.getByRole('link', { name: 'Edit', exact: true }).click();
  await expect(page).toHaveURL('/static-hosting/e2e-hosting/edit');
  await expect(page.getByLabel('Name')).not.toBeEditable();
  await page.getByRole('button', { name: 'Add domain' }).click();
  await page.getByLabel('Domain 2', { exact: true }).fill('www.hosting.example.org');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL('/static-hosting/e2e-hosting/configuration');
  await expect(page.getByTestId('domains')).toContainText('https://www.hosting.example.org/');

  // the keys are shown when the tab opens, the secret stays hidden until shown
  await page.getByRole('link', { name: 'Credentials' }).click();
  const accessKey = page.getByTestId('access-key');
  await expect(accessKey).toHaveText(/^[A-Z2-7]{20}$/);
  await expect(page.getByTestId('secret-key')).toHaveText(/^•+$/);
  const accessKeyId = (await accessKey.textContent())!;
  await page.getByRole('button', { name: 'Copy access key' }).click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(accessKeyId);
  await page.getByRole('button', { name: 'Show secret key' }).click();
  await expect(page.getByTestId('secret-key')).toHaveText(/^[A-Za-z0-9+/]{40}$/);

  // an object put with the key pair is counted with its size
  await putObject(accessKeyId, (await page.getByTestId('secret-key').textContent())!.trim(), 'e2e-hosting', 'fixture.txt', FIXTURE_OBJECT);
  await page.goto('/static-hosting/e2e-hosting/configuration');
  await expect(page.getByTestId('objects')).toHaveText('1', { timeout: 20_000 });
  await expect(page.getByTestId('bytes')).toHaveText(`${FIXTURE_OBJECT.length} B`);

  // a taken name is reported
  await page.getByRole('link', { name: 'All buckets' }).click();
  await page.getByRole('link', { name: 'Create static hosting', exact: true }).click();
  await page.getByLabel('Name').fill('e2e-hosting');
  await page.getByRole('button', { name: 'Create static hosting', exact: true }).click();
  await expect(page.getByTestId('create-errors')).toHaveText('A bucket of the name exists.');
  await page.getByRole('link', { name: 'Cancel' }).click();
  await expect(page).toHaveURL('/static-hosting');

  const other = page.getByTestId('bucket-e2e-hosting');
  await expect(other.getByTestId('objects')).toHaveText('1');
  await expect(other).toContainText('hosting.example.org');
  await expect(other).not.toContainText(accessKeyId);

  // the 409 of the taken name is the only error the browser logs
  expect(errors.filter((error) => !error.includes('409'))).toEqual([]);
});
