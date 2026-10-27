import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { errorMessages } from '../shared/api';
import { DomainList } from '../shared/domain-list';
import { enteredDomains, validDomains } from '../shared/domains';
import { formatBytes, formatCount } from '../shared/format';
import { Modal } from '../shared/modal';
import { Icon } from '../shared/icon';
import { Bucket, BucketSecret, BucketsService } from './buckets.service';
import { POLL_INTERVAL_MS } from './buckets';

// how long a copy button says it copied
export const COPIED_MS = 2000;

type Copied = 'accessKeyId' | 'secretAccessKey';

// one bucket with its statistics; its keys are only fetched when asked for, the secret stays
// hidden until it is shown or copied
@Component({
  selector: 'app-bucket',
  imports: [DatePipe, DomainList, Icon, Modal, RouterLink],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="label-mono"><a routerLink="/s3">S3 · Buckets</a></div>
        <h1 class="mb-1 font-mono">{{ name() }}</h1>
        @if (bucket(); as bucket) {
          <div class="page-subtitle">Created {{ bucket.createdAt | date: 'medium' }}</div>
        }
      </div>

      @if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      }

      @if (bucket(); as bucket) {
        <div class="card mb-3">
          <div class="card-body">
            <dl class="row mb-0">
              <dt class="col-sm-3">Domains</dt>
              <dd class="col-sm-9 d-flex flex-wrap align-items-center gap-2">
                <span class="font-mono" data-testid="domains">{{ bucket.domains.join(', ') || '–' }}</span>
                <button class="btn btn-sm btn-outline-secondary" type="button" (click)="openDialog(bucket)">Edit domains</button>
              </dd>
              @if (bucket.statistics.failed) {
                <dt class="col-sm-3">Objects</dt>
                <dd class="col-sm-9 text-secondary">could not be counted</dd>
              } @else if (bucket.statistics.computing) {
                <dt class="col-sm-3">Objects</dt>
                <dd class="col-sm-9 text-secondary">
                  <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                  counting…
                </dd>
              } @else {
                <dt class="col-sm-3">Objects</dt>
                <dd class="col-sm-9 font-mono" data-testid="objects">{{ formatCount(bucket.statistics.objects!) }}</dd>
                <dt class="col-sm-3">Size</dt>
                <dd class="col-sm-9 font-mono" data-testid="bytes">{{ formatBytes(bucket.statistics.bytes!) }}</dd>
                <dt class="col-sm-3">Counted</dt>
                <dd class="col-sm-9 text-secondary mb-0">{{ bucket.statistics.computedAt | date: 'HH:mm:ss' }}</dd>
              }
            </dl>
          </div>
        </div>

        <div class="card" aria-label="Credentials" role="region">
          <div class="card-body">
            <h2 class="h6 mb-1">Credentials</h2>
            <p class="text-secondary small">The key pair may access this bucket only.</p>
            @if (secret(); as secret) {
              <dl class="row mb-0">
                <dt class="col-sm-3">Access key</dt>
                <dd class="col-sm-9 d-flex align-items-center gap-2">
                  <code data-testid="access-key">{{ secret.accessKeyId }}</code>
                  <button
                    class="btn btn-sm btn-outline-secondary"
                    type="button"
                    aria-label="Copy access key"
                    title="Copy access key"
                    (click)="copy('accessKeyId')"
                  >
                    <app-icon [name]="copied() === 'accessKeyId' ? 'check' : 'clipboard'" />
                  </button>
                </dd>
                <dt class="col-sm-3">Secret key</dt>
                <dd class="col-sm-9 d-flex align-items-center gap-2 mb-0">
                  <code data-testid="secret-key">{{ shown() ? secret.secretAccessKey : '••••••••••••••••••••' }}</code>
                  <button
                    class="btn btn-sm btn-outline-secondary"
                    type="button"
                    [attr.aria-label]="shown() ? 'Hide secret key' : 'Show secret key'"
                    [title]="shown() ? 'Hide secret key' : 'Show secret key'"
                    [attr.aria-pressed]="shown()"
                    (click)="shown.set(!shown())"
                  >
                    <app-icon [name]="shown() ? 'eye-slash' : 'eye'" />
                  </button>
                  <button
                    class="btn btn-sm btn-outline-secondary"
                    type="button"
                    aria-label="Copy secret key"
                    title="Copy secret key"
                    (click)="copy('secretAccessKey')"
                  >
                    <app-icon [name]="copied() === 'secretAccessKey' ? 'check' : 'clipboard'" />
                  </button>
                </dd>
              </dl>
              @if (copyFailed()) {
                <div class="text-danger small mt-2" role="alert">The browser did not allow to copy.</div>
              }
            } @else {
              <button class="btn btn-outline-primary" type="button" (click)="retrieve()" [disabled]="retrieving()">
                @if (retrieving()) {
                  <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                }
                Retrieve credentials
              </button>
            }
          </div>
        </div>
      }
    </div>

    @if (dialogOpen()) {
      <app-modal title="Edit domains" (closed)="dialogOpen.set(false)">
        <form (submit)="$event.preventDefault(); saveDomains()">
          <div class="modal-body">
            <div class="mb-3">
              <label class="form-label" for="edit-bucket-name">Bucket name</label>
              <input class="form-control font-mono" id="edit-bucket-name" [value]="name()" readonly />
            </div>
            <app-domain-list [(domains)]="domains" />
            @if (saveErrors().length) {
              <div class="text-danger small mt-2" role="alert" data-testid="save-error">
                @for (message of saveErrors(); track $index) {
                  <div>{{ message }}</div>
                }
              </div>
            }
          </div>
          <div class="modal-footer">
            <button class="btn btn-outline-secondary" type="button" (click)="dialogOpen.set(false)">Cancel</button>
            <button class="btn btn-primary" type="submit" [disabled]="!validDomains(domains()) || saving()">
              @if (saving()) {
                <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
              }
              Save
            </button>
          </div>
        </form>
      </app-modal>
    }
  `,
})
export class BucketDetail implements OnInit {
  private readonly service = inject(BucketsService);

  // the route parameter
  readonly name = input.required<string>();

  protected readonly bucket = signal<Bucket | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly secret = signal<BucketSecret | null>(null);
  protected readonly retrieving = signal(false);
  protected readonly shown = signal(false);
  protected readonly copied = signal<Copied | null>(null);
  protected readonly copyFailed = signal(false);

  protected readonly dialogOpen = signal(false);
  protected readonly domains = signal<string[]>([]);
  protected readonly saving = signal(false);
  protected readonly saveErrors = signal<string[]>([]);
  protected readonly validDomains = validDomains;

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;

  private readonly subscriptions = new Subscription();
  private copiedTimer?: Subscription;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
  }

  ngOnInit(): void {
    this.load();
  }

  protected openDialog(bucket: Bucket): void {
    this.domains.set(bucket.domains.length ? [...bucket.domains] : ['']);
    this.saveErrors.set([]);
    this.dialogOpen.set(true);
  }

  protected saveDomains(): void {
    if (!validDomains(this.domains())) return;
    this.saving.set(true);
    this.saveErrors.set([]);
    this.subscriptions.add(
      this.service.updateDomains(this.name(), enteredDomains(this.domains())).subscribe({
        next: (bucket) => {
          this.saving.set(false);
          this.dialogOpen.set(false);
          this.bucket.set(bucket);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          const messages = errorMessages(error);
          this.saveErrors.set(messages.length ? messages : ['The domains could not be changed.']);
        },
      }),
    );
  }

  protected retrieve(): void {
    this.retrieving.set(true);
    this.subscriptions.add(
      this.service.secret(this.name()).subscribe({
        next: (secret) => {
          this.retrieving.set(false);
          this.secret.set(secret);
        },
        error: (error: unknown) => {
          this.retrieving.set(false);
          this.error.set(errorMessage(error, 'The credentials could not be retrieved.'));
        },
      }),
    );
  }

  protected async copy(key: Copied): Promise<void> {
    const secret = this.secret();
    if (!secret) return;
    try {
      await navigator.clipboard.writeText(secret[key]);
      this.copyFailed.set(false);
      this.copied.set(key);
      this.copiedTimer?.unsubscribe();
      this.copiedTimer = timer(COPIED_MS).subscribe(() => this.copied.set(null));
      this.subscriptions.add(this.copiedTimer);
    } catch {
      this.copyFailed.set(true);
    }
  }

  // asks again after a while as long as the bucket is still being counted
  private load(): void {
    this.subscriptions.add(
      this.service.bucket(this.name()).subscribe({
        next: (bucket) => {
          this.bucket.set(bucket);
          if (bucket.statistics.computing) {
            this.subscriptions.add(timer(POLL_INTERVAL_MS).subscribe(() => this.load()));
          }
        },
        error: (error: unknown) => this.error.set(errorMessage(error, 'The bucket could not be loaded.')),
      }),
    );
  }
}

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse && error.status === 404) return 'There is no such bucket.';
  if (error instanceof HttpErrorResponse && error.status === 403) return 'Your role does not allow to see the buckets.';
  return fallback;
}
