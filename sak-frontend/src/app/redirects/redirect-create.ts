import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { apiErrorMessage } from '../shared/api';
import {
  RedirectTargetFields,
  redirectTarget,
  TargetType,
  validDomain,
} from './redirect-target-fields';
import { RedirectsService } from './redirects.service';

// redirects a domain to an external domain or to the domain a bucket is served at, on a page
// of its own; afterwards the redirect shows
@Component({
  selector: 'app-redirect-create',
  imports: [RedirectTargetFields, RouterLink],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/redirects"
        aria-label="All redirects"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">Create redirect</h1>
    </header>

    <form class="vstack gap-3 card card-body" (submit)="$event.preventDefault(); create()">
      <div>
        <label class="form-label" for="source-domain">Domain</label>
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
        <span class="form-text"
          >Every request for it is redirected. It needs a DNS record pointing at the server.</span
        >
      </div>

      <app-redirect-target-fields [(type)]="type" [(domain)]="domain" [(bucket)]="bucket" />

      @if (createError(); as error) {
        <p class="alert alert-danger mb-0" role="alert" data-testid="create-error">{{ error }}</p>
      }

      <div class="d-flex flex-wrap justify-content-end gap-2">
        <a class="btn btn-outline-secondary" routerLink="/redirects">Cancel</a>
        <button class="btn btn-primary" type="submit" [disabled]="!complete() || creating()">
          @if (creating()) {
            <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
          } @else {
            <i class="sak-icon-plus" aria-hidden="true"></i>
          }
          Create redirect
        </button>
      </div>
    </form>
  `,
})
export class RedirectCreate {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(RedirectsService);
  private readonly router = inject(Router);

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
          this.createError.set(createErrorMessage(error, sourceDomain));
        },
      });
  }
}

// what the api answered, or what its status tells
function createErrorMessage(error: unknown, domain: string): string {
  return apiErrorMessage(error, 'The redirect could not be created.', {
    403: 'Your role does not allow to create redirects.',
    409: `${domain} is redirected already.`,
    422: 'The bucket is unknown or not served at a domain.',
  });
}
