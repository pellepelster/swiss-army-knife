import { Component, DestroyRef, inject, signal } from '@angular/core';
import { Subscription, timer } from 'rxjs';
import { Icon } from '../shared/icon';
import { BucketStore, errorMessage } from './bucket-store';
import { BucketSecret, BucketsService } from './buckets.service';

// how long a copy button says it copied
export const COPIED_MS = 2000;

type Copied = 'accessKeyId' | 'secretAccessKey';

// the credentials tab of a bucket; its keys are only fetched when asked for, the secret stays
// hidden until it is shown or copied
@Component({
  selector: 'app-bucket-credentials',
  imports: [Icon],
  template: `
    @if (error()) {
      <div class="alert alert-danger" role="alert">{{ error() }}</div>
    }

    <div class="card" aria-label="Credentials" role="region">
      <div class="card-body">
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
  `,
})
export class BucketCredentials {
  private readonly service = inject(BucketsService);
  private readonly store = inject(BucketStore);

  protected readonly error = signal<string | null>(null);
  protected readonly secret = signal<BucketSecret | null>(null);
  protected readonly retrieving = signal(false);
  protected readonly shown = signal(false);
  protected readonly copied = signal<Copied | null>(null);
  protected readonly copyFailed = signal(false);

  private readonly subscriptions = new Subscription();
  private copiedTimer?: Subscription;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
  }

  protected retrieve(): void {
    this.retrieving.set(true);
    this.subscriptions.add(
      this.service.secret(this.store.name()).subscribe({
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
}
