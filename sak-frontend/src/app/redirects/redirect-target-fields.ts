import { Component, DestroyRef, inject, model, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Bucket, BucketsService, servedDomains } from '../hosting/buckets.service';
import { TranslatePipe } from '../i18n/i18n';
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

// the target of a redirect, an external domain or a bucket; for the create and the edit page
@Component({
  selector: 'app-redirect-target-fields',
  imports: [TranslatePipe],
  host: { class: 'vstack gap-3' },
  template: `
    <fieldset>
      <legend class="form-label fs-6">{{ 'redirects.target.redirect_to' | t }}</legend>
      <div class="form-check form-check-inline">
        <input
          class="form-check-input"
          type="radio"
          name="type"
          id="target-domain"
          value="domain"
          [checked]="type() === 'domain'"
          (change)="type.set('domain')"
        />
        <label class="form-check-label" for="target-domain">{{
          'redirects.target.external_domain_choice' | t
        }}</label>
      </div>
      <div class="form-check form-check-inline">
        <input
          class="form-check-input"
          type="radio"
          name="type"
          id="target-bucket"
          value="bucket"
          [checked]="type() === 'bucket'"
          (change)="type.set('bucket')"
        />
        <label class="form-check-label" for="target-bucket">{{
          'redirects.target.bucket_choice' | t
        }}</label>
      </div>
    </fieldset>

    @if (type() === 'domain') {
      <div>
        <label class="form-label" for="target-domain-name">{{
          'redirects.target.external_domain' | t
        }}</label>
        <input
          class="form-control font-monospace text-break"
          id="target-domain-name"
          placeholder="example.org"
          autocomplete="off"
          autocapitalize="off"
          [attr.aria-invalid]="domain() !== '' && !valid(domain())"
          [class.is-invalid]="domain() !== '' && !valid(domain())"
          [value]="domain()"
          (input)="domain.set($any($event.target).value)"
        />
      </div>
    } @else {
      <div>
        <label class="form-label" for="target-bucket-name">{{
          'redirects.target.bucket' | t
        }}</label>
        <select
          class="form-select font-monospace text-break"
          id="target-bucket-name"
          [value]="bucket()"
          (change)="bucket.set($any($event.target).value)"
        >
          <option value="" disabled [selected]="bucket() === ''">
            {{ 'redirects.target.choose_bucket' | t }}
          </option>
          @for (served of servedBuckets(); track served.name) {
            <option [value]="served.name" [selected]="served.name === bucket()">
              {{ served.name }} ({{ domains(served)[0] }})
            </option>
          }
        </select>
        <span class="form-text">{{ 'redirects.target.bucket_hint' | t }}</span>
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
