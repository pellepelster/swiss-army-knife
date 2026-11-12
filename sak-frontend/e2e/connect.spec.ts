import { expect, Page, test } from '@playwright/test';
import jsQR from 'jsqr';
import { signIn } from './apps';

const ADDRESS = 'connect@example.org';

// the qr code on the page as a scanner sees it: a screenshot, its pixels read in a blank page
// (without the content security policy of the apps), decoded here
async function scan(page: Page): Promise<string> {
  const png = await page.getByTestId('qr').screenshot();
  const blank = await page.context().newPage();
  try {
    const { width, height, pixels } = await blank.evaluate(
      async (src) => {
        const image = new Image();
        image.src = src;
        await image.decode();
        const canvas = document.createElement('canvas');
        canvas.width = image.width;
        canvas.height = image.height;
        const context = canvas.getContext('2d')!;
        context.drawImage(image, 0, 0);
        const data = context.getImageData(0, 0, image.width, image.height).data;
        return { width: image.width, height: image.height, pixels: Array.from(data) };
      },
      `data:image/png;base64,${png.toString('base64')}`,
    );
    const decoded = jsQR(Uint8ClampedArray.from(pixels), width, height);
    expect(decoded).not.toBeNull();
    return decoded!.data;
  } finally {
    await blank.close();
  }
}

// an app connected with the qr code of a signed in session (SECURITY-3): the app exchanges the token
// of the link for a session of the same user, once; the page then says it is connected
test('connects an app with the qr code of the signed in user', async ({ page, request }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await signIn(page, request, ADDRESS);

  await page.getByRole('button', { name: 'Menu', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Connect an app' }).click();
  await expect(page).toHaveURL('/connect-app');
  await expect(page.getByRole('heading', { name: 'Connect an app' })).toBeVisible();
  await expect(page.getByTestId('countdown')).toHaveText(/Valid for [12]:\d\d/);

  const link = new URL(await scan(page));
  expect(link.pathname).toBe('/connect');
  // the token is in the fragment, which a browser never sends; the page never shows it
  const token = new URLSearchParams(link.hash.slice(1)).get('token')!;
  expect(token).toBeTruthy();
  await expect(page.locator('body')).not.toContainText(token);

  // what the app does with it
  const exchange = await request.post('/api/v1/auth/login/connect', { data: { token } });
  expect(exchange.status()).toBe(200);
  const session = (await exchange.json()) as { sessionToken: string; email: string };
  expect(session.email).toBe(ADDRESS);
  const whoAmI = await request.get('/api/v1/auth/who-am-i', {
    headers: { Authorization: `Bearer ${session.sessionToken}` },
  });
  expect(((await whoAmI.json()) as { username: string }).username).toBe(ADDRESS);

  await expect(page.getByTestId('connected')).toHaveText('Connected');
  await expect(page.getByTestId('qr')).toBeHidden();

  // once only
  const again = await request.post('/api/v1/auth/login/connect', { data: { token } });
  expect(again.status()).toBe(401);
  expect(errors).toEqual([]);
});
