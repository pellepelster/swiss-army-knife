import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Bucket, BucketsService } from '../s3/buckets.service';
import { errorMessages } from '../shared/api';
import { DOMAIN_PATTERN } from '../shared/domains';
import { RedirectTarget, RedirectsService } from './redirects.service';

type TargetType = RedirectTarget['type'];

// redirects a domain to an external domain or to the domain a bucket is served at, then goes
// back to the redirects
@Component({
  selector: 'app-redirect-create',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="page">
      <div class="page-header">
        <h1 class="mb-1">Add redirect</h1>
      </div>

      <form class="card" [formGroup]="form" (ngSubmit)="create()">
        <div class="card-body">
          <div class="mb-3">
            <label class="form-label" for="source-domain">Domain</label>
            <input
              class="form-control font-mono"
              id="source-domain"
              placeholder="example.com"
              autocomplete="off"
              formControlName="sourceDomain"
            />
          </div>
          <fieldset class="mb-3">
            <legend class="form-label fs-6">Redirect to</legend>
            <div class="form-check form-check-inline">
              <input class="form-check-input" type="radio" id="target-domain" value="domain" formControlName="type" />
              <label class="form-check-label" for="target-domain">External domain</label>
            </div>
            <div class="form-check form-check-inline">
              <input class="form-check-input" type="radio" id="target-bucket" value="bucket" formControlName="type" />
              <label class="form-check-label" for="target-bucket">Bucket</label>
            </div>
          </fieldset>
          @if (form.controls.type.value === 'domain') {
            <label class="form-label" for="target-domain-name">External domain</label>
            <input
              class="form-control font-mono"
              id="target-domain-name"
              placeholder="example.org"
              autocomplete="off"
              formControlName="domain"
            />
          } @else {
            <label class="form-label" for="target-bucket-name">Bucket</label>
            <select class="form-select font-mono" id="target-bucket-name" formControlName="bucket">
              <option value="" disabled>Choose a bucket</option>
              @for (bucket of servedBuckets(); track bucket.name) {
                <option [value]="bucket.name">{{ bucket.name }} ({{ bucket.domains[0] }})</option>
              }
            </select>
            <div class="form-text">Only buckets served at a domain, the redirect leads to their first one.</div>
          }
          @if (createError()) {
            <div class="text-danger small mt-2" role="alert" data-testid="create-error">{{ createError() }}</div>
          }
        </div>
        <div class="card-footer d-flex justify-content-end gap-2">
          <a class="btn btn-outline-secondary" routerLink="/redirects">Cancel</a>
          <button class="btn btn-primary" type="submit" [disabled]="!valid() || creating()">
            @if (creating()) {
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            }
            Add redirect
          </button>
        </div>
      </form>
    </div>
  `,
})
export class RedirectCreate {
  private readonly service = inject(RedirectsService);
  private readonly bucketsService = inject(BucketsService);
  private readonly router = inject(Router);

  protected readonly servedBuckets = signal<Bucket[]>([]);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly form = new FormGroup({
    sourceDomain: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(DOMAIN_PATTERN)],
    }),
    type: new FormControl<TargetType>('domain', { nonNullable: true }),
    domain: new FormControl('', { nonNullable: true, validators: [Validators.pattern(DOMAIN_PATTERN)] }),
    bucket: new FormControl('', { nonNullable: true }),
  });
  // the form's validity as a signal, the template reads it in the footer
  protected readonly valid = signal(false);

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
    this.subscriptions.add(this.form.valueChanges.subscribe(() => this.valid.set(this.isValid())));
    this.subscriptions.add(
      this.bucketsService.buckets().subscribe({
        next: (buckets) => this.servedBuckets.set(buckets.filter((bucket) => bucket.domains.length > 0)),
        error: () => this.servedBuckets.set([]),
      }),
    );
  }

  protected create(): void {
    if (!this.isValid()) return;
    const { sourceDomain, type, domain, bucket } = this.form.getRawValue();
    const target: RedirectTarget = type === 'bucket' ? { type, bucket } : { type, domain };
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

  // the target of the chosen type has to be there
  private isValid(): boolean {
    const { sourceDomain, type, domain, bucket } = this.form.controls;
    const target = type.value === 'bucket' ? bucket.value !== '' : domain.value !== '' && domain.valid;
    return sourceDomain.valid && target;
  }
}

// what the api answered, or what its status tells
function createErrorMessage(error: unknown, domain: string): string {
  const messages = errorMessages(error);
  if (messages.length) return messages.join(' ');
  if (!(error instanceof HttpErrorResponse)) return 'The redirect could not be created.';
  switch (error.status) {
    case 400:
      return `${domain} can not be redirected there: no valid domain, the domain itself, or one the console serves.`;
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
