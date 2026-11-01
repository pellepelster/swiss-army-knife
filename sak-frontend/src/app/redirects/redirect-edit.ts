import { Component, computed, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { redirectErrorMessage } from './redirect-store';
import { RedirectTargetFields, redirectTarget, TargetType } from './redirect-target-fields';
import { RedirectsService } from './redirects.service';

// edits where a redirect leads to on a page of its own, the redirected domain can not be
// changed; saving or cancelling goes back to the redirect
@Component({
  selector: 'app-redirect-edit',
  imports: [RedirectTargetFields, RouterLink, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        [routerLink]="['/redirects', source()]"
        [attr.aria-label]="'redirects.back_to_redirect' | t"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'redirects.edit_page.title' | t }}</h1>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <form class="vstack gap-3 card card-body" (submit)="$event.preventDefault(); save()">
        <div>
          <label class="form-label" for="source-domain">{{ 'redirects.domain' | t }}</label>
          <input
            class="form-control-plaintext font-monospace text-break"
            id="source-domain"
            [value]="source()"
            readonly
          />
          <span class="form-text">{{ 'redirects.edit_page.domain_fixed' | t }}</span>
        </div>

        <app-redirect-target-fields [(type)]="type" [(domain)]="domain" [(bucket)]="bucket" />

        @if (saveError(); as error) {
          <p class="alert alert-danger mb-0" role="alert" data-testid="save-error">{{ error }}</p>
        }

        <div class="d-flex flex-wrap justify-content-end gap-2">
          <a class="btn btn-outline-secondary" [routerLink]="['/redirects', source()]">{{
            'redirects.cancel' | t
          }}</a>
          <button class="btn btn-primary" type="submit" [disabled]="target() === null || saving()">
            @if (saving()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            }
            {{ 'redirects.save' | t }}
          </button>
        </div>
      </form>
    }
  `,
})
export class RedirectEdit implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(RedirectsService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  // the route parameter
  readonly source = input.required<string>();

  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly type = signal<TargetType>('domain');
  protected readonly domain = signal('');
  protected readonly bucket = signal('');
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly target = computed(() =>
    redirectTarget(this.type(), this.domain(), this.bucket()),
  );

  ngOnInit(): void {
    this.service
      .redirect(this.source())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (redirect) => {
          this.type.set(redirect.target.type);
          if (redirect.target.type === 'domain') this.domain.set(redirect.target.domain);
          else this.bucket.set(redirect.target.bucket);
          this.loaded.set(true);
        },
        error: (error: unknown) =>
          this.error.set(
            redirectErrorMessage(this.i18n, error, this.i18n.t('redirects.load_failed')),
          ),
      });
  }

  protected save(): void {
    const target = this.target();
    if (target === null) return;
    this.saving.set(true);
    this.saveError.set(null);
    this.service
      .update(this.source(), target)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigate(['/redirects', this.source()]),
        error: (error: unknown) => {
          this.saving.set(false);
          this.saveError.set(saveErrorMessage(this.i18n, error));
        },
      });
  }
}

// what the api answered, or what its status tells
function saveErrorMessage(i18n: I18n, error: unknown): string {
  return apiErrorMessage(error, i18n.t('redirects.edit_page.save_failed'), {
    403: i18n.t('redirects.edit_page.forbidden'),
    404: i18n.t('redirects.not_found'),
    422: i18n.t('redirects.bucket_unknown'),
  });
}
