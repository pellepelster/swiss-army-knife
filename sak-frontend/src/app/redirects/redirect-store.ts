import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Redirect, RedirectsService } from './redirects.service';

// the redirect of a redirect's page, shared by the page and its tabs; provided by the page
@Injectable()
export class RedirectStore {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(RedirectsService);

  readonly redirect = signal<Redirect | null>(null);
  readonly error = signal<string | null>(null);

  load(sourceDomain: string): void {
    this.service
      .redirect(sourceDomain)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (redirect) => this.redirect.set(redirect),
        error: (error: unknown) =>
          this.error.set(redirectErrorMessage(error, 'The redirect could not be loaded.')),
      });
  }
}

export function redirectErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse && error.status === 404)
    return 'There is no such redirect.';
  if (error instanceof HttpErrorResponse && error.status === 403)
    return 'Your role does not allow to see the redirects.';
  return fallback;
}
