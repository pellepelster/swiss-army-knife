import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import {
  RedirectTargetFields,
  redirectTarget,
  TargetType,
  validDomain,
} from './redirect-target-fields';
import { RedirectsService } from './redirects.service';
import { PageHeader } from '../shared/page-header';

// redirects a domain to an external domain or to the domain a bucket is served at, on a page
// of its own; afterwards the redirect shows
@Component({
  selector: 'app-redirect-create',
  imports: [PageHeader, RedirectTargetFields, RouterLink, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/redirects" [backLabel]="'redirects.all_redirects' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'redirects.create' | t }}</h1>
    </header>

    <form class="vstack gap-3 card card-body" (submit)="$event.preventDefault(); create()">
      <div>
        <label class="form-label" for="source-domain">{{ 'redirects.domain' | t }}</label>
        <input
          class="form-control font-monospace text-break"
          id="source-domain"
          placeholder="example.com"
          autocomplete="off"
          autocapitalize="off"
          [attr.aria-invalid]="sourceDomain() !== '' && !valid(sourceDomain())"
          [class.is-invalid]="sourceDomain() !== '' && !valid(sourceDomain())"
          [value]="sourceDomain()"
          (input)="sourceDomain.set($any($event.target).value)"
        />
        <span class="form-text">{{ 'redirects.create_page.domain_hint' | t }}</span>
      </div>

      <app-redirect-target-fields [(type)]="type" [(domain)]="domain" [(bucket)]="bucket" />

      @if (createError(); as error) {
        <p class="alert alert-danger mb-0" role="alert" data-testid="create-error">{{ error }}</p>
      }

      <!-- cancel and create are one group (UX-9) -->
      <div class="d-flex justify-content-end">
        <div class="btn-group" role="group" [attr.aria-label]="'shared.form_actions' | t">
          <a class="btn btn-outline-secondary" routerLink="/redirects">{{ 'shared.cancel' | t }}</a>
          <button class="btn btn-primary" type="submit" [disabled]="!complete() || creating()">
            @if (creating()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            } @else {
              <i class="sak-icon-plus" aria-hidden="true"></i>
            }
            {{ 'shared.create' | t }}
          </button>
        </div>
      </div>
    </form>
  `,
})
export class RedirectCreate {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(RedirectsService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  protected readonly sourceDomain = signal('');
  protected readonly type = signal<TargetType>('domain');
  protected readonly domain = signal('');
  protected readonly bucket = signal('');
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  // the target of the chosen type has to be there
  private readonly target = computed(() =>
    redirectTarget(this.type(), this.domain(), this.bucket()),
  );
  protected readonly complete = computed(
    () => this.valid(this.sourceDomain()) && this.target() !== null,
  );
  protected readonly valid = validDomain;

  protected create(): void {
    const target = this.target();
    if (!this.complete() || target === null) return;
    const sourceDomain = this.sourceDomain().trim();
    this.creating.set(true);
    this.createError.set(null);
    this.service
      .create(sourceDomain, target)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigate(['/redirects', sourceDomain.toLowerCase()]),
        error: (error: unknown) => {
          this.creating.set(false);
          this.createError.set(createErrorMessage(this.i18n, error, sourceDomain));
        },
      });
  }
}

// what the api answered, or what its status tells
function createErrorMessage(i18n: I18n, error: unknown, domain: string): string {
  return apiErrorMessage(error, i18n.t('redirects.create_page.failed'), {
    403: i18n.t('redirects.create_page.forbidden'),
    409: i18n.t('redirects.create_page.exists', { domain }),
    422: i18n.t('redirects.bucket_unknown'),
  });
}
