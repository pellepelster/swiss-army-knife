import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { errorMessages } from '../shared/api';
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
  styleUrls: ['../shared/app-parts.scss', '../shared/app-page.scss'],
  template: `
    <header class="toolbar">
      <a class="back" routerLink="/redirects" aria-label="All redirects"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="title">Create redirect</h1>
    </header>

    <form class="form sheet" (submit)="$event.preventDefault(); create()">
      <div class="field">
        <label class="label" for="source-domain">Domain</label>
        <input
          class="input mono"
          id="source-domain"
          placeholder="example.com"
          autocomplete="off"
          autocapitalize="off"
          [attr.aria-invalid]="sourceDomain() !== '' && !valid(sourceDomain())"
          [value]="sourceDomain()"
          (input)="sourceDomain.set($any($event.target).value)"
        />
        <span class="hint"
          >Every request for it is redirected. It needs a DNS record pointing at the server.</span
        >
      </div>

      <app-redirect-target-fields [(type)]="type" [(domain)]="domain" [(bucket)]="bucket" />

      @if (createError(); as error) {
        <p class="form-errors" role="alert" data-testid="create-error">{{ error }}</p>
      }

      <div class="actions">
        <a class="button button-quiet" routerLink="/redirects">Cancel</a>
        <button class="button" type="submit" [disabled]="!complete() || creating()">
          @if (creating()) {
            <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
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
  const messages = errorMessages(error);
  if (messages.length) return messages.join(' ');
  if (!(error instanceof HttpErrorResponse)) return 'The redirect could not be created.';
  switch (error.status) {
    case 409:
      return `${domain} is redirected already.`;
    case 422:
      return 'The bucket is unknown or not served at a domain.';
    case 403:
      return 'Your role does not allow to create redirects.';
    default:
      return 'The redirect could not be created.';
  }
}
