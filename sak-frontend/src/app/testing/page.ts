import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { SESSION_STORAGE_KEY } from '../auth/auth.service';

// what a list endpoint answers with the items, all of them on one page
export const pageOf = <T>(items: T[]) => ({
  items,
  pagination: { offset: 0, limit: 50, total: items.length },
});

// a session stored as the login stores it, valid for a minute
export function signedIn(email = 'admin@example.org', roles: string[] = ['admin']): void {
  localStorage.setItem(
    SESSION_STORAGE_KEY,
    JSON.stringify({
      token: 'session-token',
      email,
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      roles,
    }),
  );
}

// helpers on the page the router shows; harness() is called each time, every spec creates its own
export function pageHelpers(harness: () => RouterTestingHarness) {
  const page = () => harness().routeNativeElement!;
  const button = (name: string, root: ParentNode = page()) =>
    [...root.querySelectorAll<HTMLButtonElement>('button, a')].find(
      (b) => b.textContent?.trim() === name || b.getAttribute('aria-label') === name,
    ) as HTMLButtonElement;
  return {
    page,
    text: (testId: string) =>
      page().querySelector(`[data-testid="${testId}"]`)?.textContent?.trim(),
    // the button or link with the text or label
    button,
    // opens the menu of the row (shared/action-menu.ts), returns its entries
    rowMenu: async (name: string) => {
      const toggle = button(`Actions of ${name}`);
      toggle.click();
      await harness().fixture.whenStable();
      return toggle.closest('app-action-menu')!.querySelector<HTMLElement>('[role=menu]')!;
    },
    // deletes the thing named from the menu of its row, confirmed
    deleteFromRow: async (name: string) => {
      const toggle = button(`Actions of ${name}`);
      toggle.click();
      await harness().fixture.whenStable();
      const row = toggle.closest('app-action-menu')!;
      button('Delete', row.querySelector('[role=menu]')!).click();
      await harness().fixture.whenStable();
      button('Delete', row.querySelector(`[aria-label="Delete ${name}"]`)!).click();
      await harness().fixture.whenStable();
    },
    type: async (selector: string, value: string) => {
      const input = page().querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
      await harness().fixture.whenStable();
    },
    open: async (url: string) => {
      await harness().navigateByUrl(url);
      await harness().fixture.whenStable();
    },
    url: () => TestBed.inject(Router).url,
  };
}
