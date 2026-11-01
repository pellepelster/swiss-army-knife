import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { I18n } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { Subscription, timer } from 'rxjs';
import { Bucket, BucketSecret, BucketsService, POLL_INTERVAL_MS } from './buckets.service';

// the bucket of a bucket's page, shared by the page and its tabs; provided by the page
@Injectable()
export class BucketStore {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(BucketsService);
  private readonly i18n = inject(I18n);

  readonly name = signal('');
  readonly bucket = signal<Bucket | null>(null);
  readonly error = signal<string | null>(null);

  // the key pair of the bucket once it is fetched, shared by the tabs showing it
  readonly secret = signal<BucketSecret | null>(null);
  readonly retrievingSecret = signal(false);
  readonly secretError = signal<string | null>(null);

  // the load going on, its request or the timer before it asks again; a new load ends it
  private loading = new Subscription();

  constructor() {
    this.destroyRef.onDestroy(() => this.loading.unsubscribe());
  }

  // the key pair is fetched once for all the tabs
  retrieveSecret(): void {
    if (this.secret() || this.retrievingSecret()) return;
    const name = this.name();
    this.retrievingSecret.set(true);
    this.secretError.set(null);
    this.service
      .secret(name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        // the key pair of a bucket the page has left is not shown for the next
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

  // asks again after a while as long as the bucket is still being counted; the load of the
  // bucket before ends, what was shown of another bucket goes
  load(name: string): void {
    if (name !== this.name()) {
      this.bucket.set(null);
      this.error.set(null);
      this.secret.set(null);
      this.secretError.set(null);
      this.retrievingSecret.set(false);
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
