import { expect, test } from '@playwright/test';
import { FIXTURE_OBJECT } from '../playwright.config';
import { signIn } from './console';
import { deleteAllMails } from './mailpit';

test.use({ permissions: ['clipboard-read', 'clipboard-write'] });

test('creates a bucket, lists it with its objects and size and retrieves its credentials', async ({ page, request }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await deleteAllMails(request);
  await signIn(page, request, 'buckets@example.org');

  await page.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: 'Buckets' }).click();
  await expect(page).toHaveURL('/s3/buckets');

  // e2e-other holds the object playwright.config.ts put where its data goes, before it exists
  await page.getByRole('button', { name: 'Add bucket' }).click();
  const dialog = page.getByRole('dialog', { name: 'Add bucket' });
  await dialog.getByLabel('Bucket name').fill('e2e-other');
  await dialog.getByLabel('Domain 1', { exact: true }).fill('other.example.org');
  await dialog.getByRole('button', { name: 'Add bucket' }).click();
  await expect(page).toHaveURL('/s3/buckets/e2e-other/configuration');
  await expect(page.getByTestId('objects')).toHaveText('1', { timeout: 20_000 });
  await expect(page.getByTestId('bytes')).toHaveText(`${FIXTURE_OBJECT.length} B`);
  await expect(page.getByTestId('domains')).toHaveText('other.example.org');

  // the domains change on the edit page, the name can not
  await page.getByRole('link', { name: 'Edit', exact: true }).click();
  await expect(page).toHaveURL('/s3/buckets/e2e-other/edit');
  await expect(page.getByLabel('Bucket name')).toHaveValue('e2e-other');
  await expect(page.getByLabel('Bucket name')).not.toBeEditable();
  await page.getByRole('button', { name: 'Add domain' }).click();
  await page.getByLabel('Domain 2', { exact: true }).fill('www.other.example.org');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL('/s3/buckets/e2e-other/configuration');
  await expect(page.getByTestId('domains')).toHaveText('other.example.org, www.other.example.org');

  // the keys are fetched when asked for, the secret stays hidden until shown
  await page.getByRole('link', { name: 'Credentials' }).click();
  await page.getByRole('button', { name: 'Retrieve credentials' }).click();
  const accessKey = page.getByTestId('access-key');
  await expect(accessKey).toHaveText(/^[A-Z2-7]{20}$/);
  await expect(page.getByTestId('secret-key')).toHaveText(/^•+$/);
  const credentials = page.getByRole('region', { name: 'Credentials' });
  const accessKeyId = (await accessKey.textContent())!;
  await credentials.getByRole('button', { name: 'Copy access key' }).click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(accessKeyId);
  await credentials.getByRole('button', { name: 'Show secret key' }).click();
  await expect(page.getByTestId('secret-key')).toHaveText(/^[A-Za-z0-9+/]{40}$/);

  // a taken name is reported
  await page.getByRole('link', { name: 'S3 · Buckets' }).click();
  await page.getByRole('button', { name: 'Add bucket' }).click();
  await dialog.getByLabel('Bucket name').fill('e2e-other');
  await dialog.getByRole('button', { name: 'Remove domain 1' }).click();
  await dialog.getByRole('button', { name: 'Add bucket' }).click();
  await expect(dialog.getByTestId('create-error')).toHaveText('A bucket of the name exists.');
  await dialog.getByRole('button', { name: 'Cancel' }).click();

  const other = page.getByTestId('bucket-e2e-other');
  await expect(other.getByTestId('objects')).toHaveText('1');
  await expect(other).toContainText('other.example.org');
  await expect(other).not.toContainText(accessKeyId);

  // the 409 of the taken name is the only error the browser logs
  expect(errors.filter((error) => !error.includes('409'))).toEqual([]);
});
