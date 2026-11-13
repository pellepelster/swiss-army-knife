import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { redirectErrorMessage } from './redirect-store';
import { RedirectTargetFields, redirectTarget, TargetType } from './redirect-target-fields';
import { RedirectsService } from './redirects.service';
import { PageHeader } from '../shared/page-header';

// edits the target of a redirect, not its source domain; save and cancel go back to the redirect
@Component({
  selector: 'app-redirect-edit',
  imports: [PageHeader, RedirectTargetFields, RouterLink, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header
      appPageHeader
      [back]="['/redirects', source()]"
      [backLabel]="'redirects.back_to_redirect' | t"
    >
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

        <!-- cancel and save are one group (UX-9) -->
        <div class="d-flex justify-content-end">
          <div class="btn-group" role="group" [attr.aria-label]="'shared.form_actions' | t">
            <a class="btn btn-outline-secondary" [routerLink]="['/redirects', source()]">{{
              'shared.cancel' | t
            }}</a>
            <button
              class="btn btn-primary"
              type="submit"
              [disabled]="target() === null || saving()"
            >
              @if (saving()) {
                <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
              }
              {{ 'shared.save' | t }}
            </button>
          </div>
        </div>
      </form>
    } @else if (!error()) {
      <p class="text-body-secondary">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class RedirectEdit {
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

  constructor() {
    // loads again for another route; a late answer for the redirect before never fills the form
    effect((onCleanup) => {
      const source = this.source();
      untracked(() => {
        this.loaded.set(false);
        this.error.set(null);
        this.saveError.set(null);
      });
      const subscription = this.service.redirect(source).subscribe({
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
      onCleanup(() => subscription.unsubscribe());
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

function saveErrorMessage(i18n: I18n, error: unknown): string {
  return apiErrorMessage(error, i18n.t('redirects.edit_page.save_failed'), {
    403: i18n.t('redirects.edit_page.forbidden'),
    404: i18n.t('redirects.not_found'),
    422: i18n.t('redirects.bucket_unknown'),
  });
}
