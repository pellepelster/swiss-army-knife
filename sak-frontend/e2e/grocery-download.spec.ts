import { expect, test } from '@playwright/test';

// without a session: the page to download the android app is public; the controller of the tests
// runs no release, so it has no apk to download
test('the download of the grocery app needs no sign-in', async ({ page }) => {
  await page.goto('/grocery/download/app');
  await expect(page).toHaveURL('/grocery/download/app');
  await expect(page.getByRole('heading', { name: 'The grocery app for Android' })).toBeVisible();
  await expect(
    page.getByText('This SAK instance runs no release, there is no app to download.'),
  ).toBeVisible();
});
