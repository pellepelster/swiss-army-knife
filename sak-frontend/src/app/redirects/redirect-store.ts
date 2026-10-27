import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { Redirect, RedirectsService } from './redirects.service';

// the redirect of a redirect's page, shared by the page and its tabs; provided by the page
@Injectable()
export class RedirectStore {
  private readonly service = inject(RedirectsService);

  readonly redirect = signal<Redirect | null>(null);
  readonly error = signal<string | null>(null);

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
  }

  load(sourceDomain: string): void {
    this.subscriptions.add(
      this.service.redirect(sourceDomain).subscribe({
        next: (redirect) => this.redirect.set(redirect),
        error: (error: unknown) =>
          this.error.set(redirectErrorMessage(error, 'The redirect could not be loaded.')),
      }),
    );
  }
}

export function redirectErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse && error.status === 404)
    return 'There is no such redirect.';
  if (error instanceof HttpErrorResponse && error.status === 403)
    return 'Your role does not allow to see the redirects.';
  return fallback;
}
