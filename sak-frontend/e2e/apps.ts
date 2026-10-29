import { APIRequestContext, Page, expect } from '@playwright/test';
import { appendFileSync } from 'node:fs';
import { join } from 'node:path';
import { ACCESS_LOG_DIR } from '../playwright.config';
import { loginLinkFor } from './mailpit';

export const ADMIN = 'admin@example.org';

// signs in with the link e-mailed to the address on the way to the page, ends on it; every spec
// signs in with an address of its own (e2e/backend-config.yaml), the controller sends at
// most five links an hour to each
export async function signIn(
  page: Page,
  request: APIRequestContext,
  address: string,
  path = '/',
): Promise<void> {
  await page.goto(path);
  await expect(page).toHaveURL(/\/login\?redirect=/);
  await page.getByLabel('E-mail address').fill(address);
  await page.getByRole('button', { name: 'Send login link' }).click();
  await page.goto(await loginLinkFor(request, address));
  await expect(page).toHaveURL(path);
  expect(await page.evaluate(() => localStorage.getItem('sak.session'))).toContain(address);
}

// on the index page
export async function signOut(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByRole('button', { name: 'Sign out' }).click();
}

export interface AccessLogEntry {
  site: string;
  host?: string;
  status: number;
  size: number;
  uri: string;
  userAgent: string;
}

// writes entries like the ingress does, vector picks them up from there
export function appendAccessLog(...entries: AccessLogEntry[]): void {
  const lines = entries.map((entry) =>
    JSON.stringify({
      level: 'info',
      ts: Date.now() / 1000,
      logger: 'http.log.access',
      msg: 'handled request',
      request: {
        remote_ip: '192.0.2.0',
        proto: 'HTTP/2.0',
        method: 'GET',
        host: entry.host ?? 'example.org',
        uri: entry.uri,
        headers: { 'User-Agent': [entry.userAgent] },
      },
      site: entry.site,
      bytes_read: 0,
      duration: 0.001,
      size: entry.size,
      status: entry.status,
    }),
  );
  appendFileSync(join(ACCESS_LOG_DIR, 'access.log'), lines.join('\n') + '\n');
}

// creates the bucket served at the domains through the api with the session of the signed in page,
// unless it exists already; the specs that need a bucket as a site make sure it is there
export async function ensureBucket(page: Page, name: string, domains: string[]): Promise<void> {
  const status = await page.evaluate(
    async ({ name, domains }) => {
      const session = JSON.parse(localStorage.getItem('sak.session') ?? '{}') as { token?: string };
      const response = await fetch('/api/v1/static-hosting', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.token}` },
        body: JSON.stringify({ name, domains }),
      });
      return response.status;
    },
    { name, domains },
  );
  expect([201, 409]).toContain(status);
}
