import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { I18n } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { Redirect, RedirectsService } from './redirects.service';

// the redirect of a redirect's page, shared by the page and its tabs; provided by the page
@Injectable()
export class RedirectStore {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(RedirectsService);
  private readonly i18n = inject(I18n);

  readonly redirect = signal<Redirect | null>(null);
  readonly error = signal<string | null>(null);

  load(sourceDomain: string): void {
    this.service
      .redirect(sourceDomain)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (redirect) => this.redirect.set(redirect),
        error: (error: unknown) =>
          this.error.set(
            redirectErrorMessage(this.i18n, error, this.i18n.t('redirects.load_failed')),
          ),
      });
  }
}

export function redirectErrorMessage(i18n: I18n, error: unknown, fallback: string): string {
  return apiErrorMessage(error, fallback, {
    403: i18n.t('redirects.forbidden'),
    404: i18n.t('redirects.not_found'),
  });
}
