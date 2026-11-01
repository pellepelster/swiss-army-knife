import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { Subscription } from 'rxjs';
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

  // the redirect loaded last and its load going on; a new load ends it
  private sourceDomain = '';
  private loading = new Subscription();

  constructor() {
    this.destroyRef.onDestroy(() => this.loading.unsubscribe());
  }

  // what was shown of another redirect goes
  load(sourceDomain: string): void {
    if (sourceDomain !== this.sourceDomain) {
      this.redirect.set(null);
      this.error.set(null);
    }
    this.sourceDomain = sourceDomain;
    this.loading.unsubscribe();
    this.loading = this.service.redirect(sourceDomain).subscribe({
      next: (redirect) => this.redirect.set(redirect),
      error: (error: unknown) =>
        this.error.set(
          redirectErrorMessage(this.i18n, error, this.i18n.t('redirects.load_failed')),
        ),
    });
  }
}

// what to tell about a delete that failed
export function redirectDeleteErrorMessage(i18n: I18n, error: unknown, domain: string): string {
  return apiErrorMessage(error, i18n.t('redirects.view.delete_failed', { domain }), {
    403: i18n.t('redirects.view.delete_forbidden'),
  });
}

export function redirectErrorMessage(i18n: I18n, error: unknown, fallback: string): string {
  return apiErrorMessage(error, fallback, {
    403: i18n.t('redirects.forbidden'),
    404: i18n.t('redirects.not_found'),
  });
}
