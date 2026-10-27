import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Bucket, BucketsService, servedDomains } from '../hosting/buckets.service';
import { errorMessages } from '../shared/api';
import { DOMAIN_PATTERN } from '../shared/domains';
import { RedirectsService, RedirectTarget } from './redirects.service';

type TargetType = RedirectTarget['type'];

// redirects a domain to an external domain or to the domain a bucket is served at, on a page
// of its own; afterwards the redirects show
@Component({
  selector: 'app-redirect-create',
  imports: [RouterLink],
  styleUrls: ['../shared/app-parts.scss', '../shared/app-page.scss'],
  styles: `
    fieldset {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 20px;
      margin: 0;
      padding: 0;
      border: 0;
    }
    legend {
      margin-bottom: 6px;
    }
    .choice {
      display: flex;
      align-items: center;
      gap: 6px;
    }
  `,
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

      <fieldset>
        <legend class="label">Redirect to</legend>
        <label class="choice">
          <input
            type="radio"
            name="type"
            id="target-domain"
            value="domain"
            [checked]="type() === 'domain'"
            (change)="type.set('domain')"
          />
          An external domain
        </label>
        <label class="choice">
          <input
            type="radio"
            name="type"
            id="target-bucket"
            value="bucket"
            [checked]="type() === 'bucket'"
            (change)="type.set('bucket')"
          />
          A bucket
        </label>
      </fieldset>

      @if (type() === 'domain') {
        <div class="field">
          <label class="label" for="target-domain-name">External domain</label>
          <input
            class="input mono"
            id="target-domain-name"
            placeholder="example.org"
            autocomplete="off"
            autocapitalize="off"
            [attr.aria-invalid]="domain() !== '' && !valid(domain())"
            [value]="domain()"
            (input)="domain.set($any($event.target).value)"
          />
        </div>
      } @else {
        <div class="field">
          <label class="label" for="target-bucket-name">Bucket</label>
          <select
            class="input mono"
            id="target-bucket-name"
            [value]="bucket()"
            (change)="bucket.set($any($event.target).value)"
          >
            <option value="" disabled [selected]="bucket() === ''">Choose a bucket</option>
            @for (served of servedBuckets(); track served.name) {
              <option [value]="served.name">{{ served.name }} ({{ domains(served)[0] }})</option>
            }
          </select>
          <span class="hint"
            >Only buckets served at a domain, the redirect leads to their first one.</span
          >
        </div>
      }

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
  private readonly service = inject(RedirectsService);
  private readonly router = inject(Router);

  protected readonly sourceDomain = signal('');
  protected readonly type = signal<TargetType>('domain');
  protected readonly domain = signal('');
  protected readonly bucket = signal('');
  protected readonly servedBuckets = signal<Bucket[]>([]);
  protected readonly domains = servedDomains;
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  // the target of the chosen type has to be there
  protected readonly complete = computed(
    () =>
      this.valid(this.sourceDomain()) &&
      (this.type() === 'bucket' ? this.bucket() !== '' : this.valid(this.domain())),
  );

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
    this.subscriptions.add(
      inject(BucketsService)
        .buckets()
        .subscribe({
          next: (buckets) =>
            this.servedBuckets.set(buckets.filter((bucket) => servedDomains(bucket).length > 0)),
          error: () => this.servedBuckets.set([]),
        }),
    );
  }

  protected valid(domain: string): boolean {
    return DOMAIN_PATTERN.test(domain.trim());
  }

  protected create(): void {
    if (!this.complete()) return;
    const sourceDomain = this.sourceDomain().trim();
    const target: RedirectTarget =
      this.type() === 'bucket'
        ? { type: 'bucket', bucket: this.bucket() }
        : { type: 'domain', domain: this.domain().trim() };
    this.creating.set(true);
    this.createError.set(null);
    this.subscriptions.add(
      this.service.create(sourceDomain, target).subscribe({
        next: () => void this.router.navigate(['/redirects']),
        error: (error: unknown) => {
          this.creating.set(false);
          this.createError.set(createErrorMessage(error, sourceDomain));
        },
      }),
    );
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
