import { Component, computed, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { apiErrorMessage } from '../shared/api';
import { redirectErrorMessage } from './redirect-store';
import { RedirectTargetFields, redirectTarget, TargetType } from './redirect-target-fields';
import { RedirectsService } from './redirects.service';

// edits where a redirect leads to on a page of its own, the redirected domain can not be
// changed; saving or cancelling goes back to the redirect
@Component({
  selector: 'app-redirect-edit',
  imports: [RedirectTargetFields, RouterLink],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        [routerLink]="['/redirects', source()]"
        aria-label="Back to the redirect"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">Edit redirect</h1>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <form class="vstack gap-3 card card-body" (submit)="$event.preventDefault(); save()">
        <div>
          <label class="form-label" for="source-domain">Domain</label>
          <input
            class="form-control-plaintext font-monospace text-break"
            id="source-domain"
            [value]="source()"
            readonly
          />
          <span class="form-text">The redirected domain can not be changed.</span>
        </div>

        <app-redirect-target-fields [(type)]="type" [(domain)]="domain" [(bucket)]="bucket" />

        @if (saveError(); as error) {
          <p class="alert alert-danger mb-0" role="alert" data-testid="save-error">{{ error }}</p>
        }

        <div class="d-flex flex-wrap justify-content-end gap-2">
          <a class="btn btn-outline-secondary" [routerLink]="['/redirects', source()]">Cancel</a>
          <button class="btn btn-primary" type="submit" [disabled]="target() === null || saving()">
            @if (saving()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            }
            Save
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
          this.error.set(redirectErrorMessage(error, 'The redirect could not be loaded.')),
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
          this.saveError.set(saveErrorMessage(error));
        },
      });
  }
}

// what the api answered, or what its status tells
function saveErrorMessage(error: unknown): string {
  return apiErrorMessage(error, 'The redirect could not be saved.', {
    403: 'Your role does not allow to change redirects.',
    404: 'There is no such redirect.',
    422: 'The bucket is unknown or not served at a domain.',
  });
}
