import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Bucket, BucketsService } from '../s3/buckets.service';
import { errorMessages } from '../shared/api';
import { Modal } from '../shared/modal';
import { DOMAIN_PATTERN } from '../shared/domains';
import { Redirect, RedirectTarget, RedirectsService } from './redirects.service';

type TargetType = RedirectTarget['type'];

// the domains caddy redirects, to an external domain or to the domain a bucket is served at
@Component({
  selector: 'app-redirects',
  imports: [Modal, ReactiveFormsModule, RouterLink],
  template: `
    <div class="page">
      <div class="page-header d-flex flex-wrap align-items-end justify-content-between gap-2">
        <div>
          <div class="label-mono">Ingress</div>
          <h1 class="mb-1">Redirects</h1>
          <div class="page-subtitle">Every request for a redirected domain is answered with a permanent redirect.</div>
        </div>
        <button class="btn btn-primary" type="button" (click)="openDialog()">Add redirect</button>
      </div>

      @if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      }

      <div class="card">
        <table class="table mb-0" aria-label="Redirects">
          <thead>
            <tr>
              <th scope="col">Domain</th>
              <th scope="col">Target</th>
              <th scope="col">Redirects to</th>
              <th scope="col"><span class="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (redirect of redirects(); track redirect.sourceDomain) {
              <tr [attr.data-testid]="'redirect-' + redirect.sourceDomain">
                <td class="font-mono">{{ redirect.sourceDomain }}</td>
                <td>
                  @if (redirect.target.type === 'bucket') {
                    <span class="badge text-bg-secondary me-1">bucket</span>
                    <a class="font-mono" [routerLink]="['/s3/buckets', redirect.target.bucket]">{{ redirect.target.bucket }}</a>
                  } @else {
                    <span class="badge text-bg-secondary me-1">domain</span>
                    <span class="font-mono">{{ redirect.target.domain }}</span>
                  }
                </td>
                <td class="font-mono" data-testid="location">
                  @if (redirect.location) {
                    https://{{ redirect.location }}
                  } @else {
                    <span class="text-secondary">nowhere, the bucket is not served at a domain</span>
                  }
                </td>
                <td class="text-end">
                  @if (confirming() === redirect.sourceDomain) {
                    <button class="btn btn-sm btn-danger me-1" type="button" (click)="delete(redirect)">Delete</button>
                    <button class="btn btn-sm btn-outline-secondary" type="button" (click)="confirming.set(null)">
                      Cancel
                    </button>
                  } @else {
                    <button
                      class="btn btn-sm btn-outline-danger"
                      type="button"
                      [attr.aria-label]="'Delete the redirect of ' + redirect.sourceDomain"
                      (click)="confirming.set(redirect.sourceDomain)"
                    >
                      Delete
                    </button>
                  }
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4" class="text-center text-secondary py-4">No redirects.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </div>

    @if (dialogOpen()) {
      <app-modal title="Add redirect" (closed)="closeDialog()">
        <form [formGroup]="form" (ngSubmit)="create()">
          <div class="modal-body">
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
          <div class="modal-footer">
            <button class="btn btn-outline-secondary" type="button" (click)="closeDialog()">Cancel</button>
            <button class="btn btn-primary" type="submit" [disabled]="!valid() || creating()">
              @if (creating()) {
                <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
              }
              Add redirect
            </button>
          </div>
        </form>
      </app-modal>
    }
  `,
})
export class Redirects {
  private readonly service = inject(RedirectsService);
  private readonly bucketsService = inject(BucketsService);

  protected readonly redirects = signal<Redirect[]>([]);
  protected readonly servedBuckets = signal<Bucket[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly confirming = signal<string | null>(null);

  protected readonly dialogOpen = signal(false);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly form = new FormGroup({
    sourceDomain: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(DOMAIN_PATTERN)] }),
    type: new FormControl<TargetType>('domain', { nonNullable: true }),
    domain: new FormControl('', { nonNullable: true, validators: [Validators.pattern(DOMAIN_PATTERN)] }),
    bucket: new FormControl('', { nonNullable: true }),
  });
  // the form's validity as a signal, the template reads it in the dialog's footer
  protected readonly valid = signal(false);

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
    this.subscriptions.add(this.form.valueChanges.subscribe(() => this.valid.set(this.isValid())));
    this.load();
  }

  protected openDialog(): void {
    this.form.reset();
    this.createError.set(null);
    this.valid.set(false);
    this.dialogOpen.set(true);
    this.subscriptions.add(
      this.bucketsService.buckets().subscribe({
        next: (buckets) => this.servedBuckets.set(buckets.filter((bucket) => bucket.domains.length > 0)),
        error: () => this.servedBuckets.set([]),
      }),
    );
  }

  protected closeDialog(): void {
    this.dialogOpen.set(false);
  }

  protected create(): void {
    if (!this.isValid()) return;
    const { sourceDomain, type, domain, bucket } = this.form.getRawValue();
    const target: RedirectTarget = type === 'bucket' ? { type, bucket } : { type, domain };
    this.creating.set(true);
    this.createError.set(null);
    this.subscriptions.add(
      this.service.create(sourceDomain, target).subscribe({
        next: () => {
          this.creating.set(false);
          this.dialogOpen.set(false);
          this.load();
        },
        error: (error: unknown) => {
          this.creating.set(false);
          this.createError.set(createErrorMessage(error, sourceDomain));
        },
      }),
    );
  }

  protected delete(redirect: Redirect): void {
    this.confirming.set(null);
    this.subscriptions.add(
      this.service.delete(redirect.sourceDomain).subscribe({
        next: () => this.load(),
        error: () => this.error.set(`The redirect of ${redirect.sourceDomain} could not be deleted.`),
      }),
    );
  }

  // the target of the chosen type has to be there
  private isValid(): boolean {
    const { sourceDomain, type, domain, bucket } = this.form.controls;
    const target = type.value === 'bucket' ? bucket.value !== '' : domain.value !== '' && domain.valid;
    return sourceDomain.valid && target;
  }

  private load(): void {
    this.subscriptions.add(
      this.service.redirects().subscribe({
        next: (redirects) => {
          this.error.set(null);
          this.redirects.set(redirects);
        },
        error: (error: unknown) =>
          this.error.set(
            error instanceof HttpErrorResponse && error.status === 403
              ? 'Your role does not allow to see the redirects.'
              : 'The redirects could not be loaded.',
          ),
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
