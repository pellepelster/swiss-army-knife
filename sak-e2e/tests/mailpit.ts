import { expect, type APIRequestContext } from '@playwright/test';
import { FRONTEND_URL, MAILPIT_URL } from '../playwright.config';

interface MessageSummary {
  ID: string;
  To: { Address: string }[];
  Subject: string;
}

// every mail is dropped, so the next one found is the one a test asked for
export async function clearInbox(request: APIRequestContext): Promise<void> {
  const deleted = await request.delete(`${MAILPIT_URL}/api/v1/messages`);
  expect(deleted.ok(), 'mailpit of `mise run env:run` is reachable').toBe(true);
}

async function messagesTo(request: APIRequestContext, address: string): Promise<MessageSummary[]> {
  const response = await request.get(`${MAILPIT_URL}/api/v1/messages`);
  expect(response.ok()).toBe(true);
  const { messages } = (await response.json()) as { messages: MessageSummary[] };
  return messages.filter((message) => message.To.some((to) => to.Address === address));
}

// the link of the login mail to the address, waited for until it arrives
export async function loginLink(request: APIRequestContext, address: string): Promise<string> {
  let id: string | undefined;
  await expect
    .poll(async () => (id = (await messagesTo(request, address))[0]?.ID), {
      message: `a login mail to ${address}`,
      timeout: 15_000,
    })
    .toBeTruthy();
  const message = await request.get(`${MAILPIT_URL}/api/v1/message/${id}`);
  const { Subject, Text } = (await message.json()) as { Subject: string; Text: string };
  expect(Subject).toMatch(/^Sign in to /);
  const link = Text.split(/\s+/).find((word) => word.startsWith(`${FRONTEND_URL}/signin?token=`));
  expect(link, `a link to ${FRONTEND_URL}/signin in the mail`).toBeTruthy();
  return link!;
}

// how many mails went to the address
export async function mailsTo(request: APIRequestContext, address: string): Promise<number> {
  return (await messagesTo(request, address)).length;
}
