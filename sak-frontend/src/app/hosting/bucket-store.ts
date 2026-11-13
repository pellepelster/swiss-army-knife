import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { I18n } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { Subscription, timer } from 'rxjs';
import { Bucket, BucketSecret, BucketsService, POLL_INTERVAL_MS } from './buckets.service';

// the bucket of a bucket's page and its tabs; provided by the page
@Injectable()
export class BucketStore {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(BucketsService);
  private readonly i18n = inject(I18n);

  readonly name = signal('');
  readonly bucket = signal<Bucket | null>(null);
  readonly error = signal<string | null>(null);

  // fetched once for all the tabs
  readonly secret = signal<BucketSecret | null>(null);
  readonly retrievingSecret = signal(false);
  readonly secretError = signal<string | null>(null);

  // the load going on, its request or the timer before it asks again; a new load ends it
  private loading = new Subscription();

  constructor() {
    this.destroyRef.onDestroy(() => this.loading.unsubscribe());
  }

  retrieveSecret(): void {
    if (this.secret() || this.retrievingSecret()) return;
    const name = this.name();
    this.retrievingSecret.set(true);
    this.secretError.set(null);
    this.service
      .secret(name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        // not shown for the next bucket once the page left this one
        next: (secret) => {
          if (name !== this.name()) return;
          this.retrievingSecret.set(false);
          this.secret.set(secret);
        },
        error: (error: unknown) => {
          if (name !== this.name()) return;
          this.retrievingSecret.set(false);
          this.secretError.set(
            bucketErrorMessage(this.i18n, error, this.i18n.t('hosting.credentials.keys_failed')),
          );
        },
      });
  }

  // the new key pair shows at once, the one before stops working
  readonly replacingSecret = signal(false);
  readonly secretReplaced = signal(false);

  replaceSecret(): void {
    if (this.replacingSecret()) return;
    const name = this.name();
    this.replacingSecret.set(true);
    this.secretReplaced.set(false);
    this.secretError.set(null);
    this.service
      .replaceSecret(name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (secret) => {
          if (name !== this.name()) return;
          this.replacingSecret.set(false);
          this.secret.set(secret);
          this.secretReplaced.set(true);
        },
        error: (error: unknown) => {
          if (name !== this.name()) return;
          this.replacingSecret.set(false);
          this.secretError.set(
            bucketErrorMessage(this.i18n, error, this.i18n.t('hosting.credentials.replace_failed')),
          );
        },
      });
  }

  // polls while the bucket is counted; ends the load before and clears another bucket
  load(name: string): void {
    if (name !== this.name()) {
      this.bucket.set(null);
      this.error.set(null);
      this.secret.set(null);
      this.secretError.set(null);
      this.retrievingSecret.set(false);
      this.secretReplaced.set(false);
    }
    this.name.set(name);
    this.loading.unsubscribe();
    this.loading = this.service.bucket(name).subscribe({
      next: (bucket) => {
        this.bucket.set(bucket);
        if (bucket.statistics.computing) {
          this.loading = timer(POLL_INTERVAL_MS).subscribe(() => this.load(name));
        }
      },
      error: (error: unknown) =>
        this.error.set(bucketErrorMessage(this.i18n, error, this.i18n.t('hosting.load_failed'))),
    });
  }
}

export function bucketErrorMessage(i18n: I18n, error: unknown, fallback: string): string {
  return apiErrorMessage(error, fallback, {
    403: i18n.t('hosting.forbidden'),
    404: i18n.t('hosting.not_found'),
  });
}
