import { Component, DestroyRef, inject, model, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Bucket, BucketsService, servedDomains } from '../hosting/buckets.service';
import { DOMAIN_PATTERN } from '../shared/domains';
import { RedirectTarget } from './redirects.service';

export type TargetType = RedirectTarget['type'];

export function validDomain(domain: string): boolean {
  return DOMAIN_PATTERN.test(domain.trim());
}

// the target of the fields, null while the one of the chosen type is missing or invalid
export function redirectTarget(
  type: TargetType,
  domain: string,
  bucket: string,
): RedirectTarget | null {
  if (type === 'bucket') return bucket === '' ? null : { type: 'bucket', bucket };
  return validDomain(domain) ? { type: 'domain', domain: domain.trim() } : null;
}

// where a redirect leads to, an external domain or a bucket served at a domain; shared by the
// pages creating and editing a redirect
@Component({
  selector: 'app-redirect-target-fields',
  styles: `
    :host {
      display: contents;
    }
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
            <option [value]="served.name" [selected]="served.name === bucket()">
              {{ served.name }} ({{ domains(served)[0] }})
            </option>
          }
        </select>
        <span class="hint"
          >Only buckets served at a domain, the redirect leads to their first one.</span
        >
      </div>
    }
  `,
})
export class RedirectTargetFields {
  private readonly destroyRef = inject(DestroyRef);
  readonly type = model<TargetType>('domain');
  readonly domain = model('');
  readonly bucket = model('');

  protected readonly servedBuckets = signal<Bucket[]>([]);
  protected readonly domains = servedDomains;
  protected readonly valid = validDomain;

  constructor() {
    inject(BucketsService)
      .buckets()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (buckets) =>
          this.servedBuckets.set(buckets.filter((bucket) => servedDomains(bucket).length > 0)),
        error: () => this.servedBuckets.set([]),
      });
  }
}
