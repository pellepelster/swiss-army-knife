import { expect, test } from '@playwright/test';
import { FIXTURE_OBJECT } from '../playwright.config';
import { signIn } from './console';
import { deleteAllMails } from './mailpit';

test('lists the buckets with their objects and size', async ({ page, request }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await deleteAllMails(request);
  await signIn(page, request, 'buckets@example.org');

  await page.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: 'Buckets' }).click();
  await expect(page).toHaveURL('/s3');

  // e2e-other holds the object playwright.config.ts put there, e2e-stats nothing
  const other = page.getByTestId('bucket-e2e-other');
  await expect(other.getByTestId('objects')).toHaveText('1', { timeout: 20_000 });
  await expect(other.getByTestId('bytes')).toHaveText(`${FIXTURE_OBJECT.length} B`);
  await expect(other).toContainText('other.example.org');
  await expect(page.getByTestId('bucket-e2e-stats').getByTestId('objects')).toHaveText('0');

  expect(errors).toEqual([]);
});
