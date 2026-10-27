import { APIRequestContext, expect } from '@playwright/test';
import { MAILPIT_URL } from '../playwright.config';

interface MessageSummary {
  ID: string;
  Subject: string;
}

export async function deleteAllMails(request: APIRequestContext): Promise<void> {
  expect((await request.delete(`${MAILPIT_URL}/api/v1/messages`)).ok()).toBe(true);
}

async function mailsTo(request: APIRequestContext, address: string): Promise<MessageSummary[]> {
  const response = await request.get(`${MAILPIT_URL}/api/v1/search`, {
    params: { query: `to:${address}` },
  });
  expect(response.ok()).toBe(true);
  return ((await response.json()) as { messages: MessageSummary[] }).messages;
}

// waits for the login mail to the address and returns the link in it
export async function loginLinkFor(request: APIRequestContext, address: string): Promise<string> {
  let messages: MessageSummary[] = [];
  await expect
    .poll(async () => (messages = await mailsTo(request, address)).length, { timeout: 10_000 })
    .toBe(1);
  expect(messages[0].Subject).toBe('Sign in to example.org');

  const message = await request.get(`${MAILPIT_URL}/api/v1/message/${messages[0].ID}`);
  const text = ((await message.json()) as { Text: string }).Text;
  const link = text.match(/^http\S+\/signin\?\S+$/m)?.[0];
  expect(link).toBeDefined();
  return link!;
}

export async function expectNoMailTo(request: APIRequestContext, address: string): Promise<void> {
  // the controller sends in the background, give it the time a sent mail would need
  await new Promise((resolve) => setTimeout(resolve, 2_000));
  expect(await mailsTo(request, address)).toEqual([]);
}
