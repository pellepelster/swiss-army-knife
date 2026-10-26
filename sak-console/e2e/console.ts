import { APIRequestContext, Page, expect } from '@playwright/test';
import { appendFileSync } from 'node:fs';
import { join } from 'node:path';
import { ACCESS_LOG_DIR } from '../playwright.config';
import { loginLinkFor } from './mailpit';

export const ADMIN = 'admin@example.org';

// signs in with the link e-mailed to the address, ends on the page the login started from; every
// spec signs in with an address of its own (e2e/controller-config.yaml), the controller sends
// at most five links an hour to each
export async function signIn(page: Page, request: APIRequestContext, address: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('E-mail address').fill(address);
  await page.getByRole('button', { name: 'Send login link' }).click();
  await page.goto(await loginLinkFor(request, address));
  await expect(page.getByTestId('personal-menu')).toContainText(address);
}

export async function signOut(page: Page): Promise<void> {
  await page.getByTestId('personal-menu').click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
}

export interface AccessLogEntry {
  site: string;
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
        host: 'example.org',
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
