import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { apiErrorMessage } from '../shared/api';
import { timer } from 'rxjs';
import { Bucket, BucketSecret, BucketsService, POLL_INTERVAL_MS } from './buckets.service';

// the bucket of a bucket's page, shared by the page and its tabs; provided by the page
@Injectable()
export class BucketStore {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(BucketsService);

  readonly name = signal('');
  readonly bucket = signal<Bucket | null>(null);
  readonly error = signal<string | null>(null);

  // the key pair of the bucket once it is fetched, shared by the tabs showing it
  readonly secret = signal<BucketSecret | null>(null);
  readonly retrievingSecret = signal(false);
  readonly secretError = signal<string | null>(null);

  // the key pair is fetched once for all the tabs
  retrieveSecret(): void {
    if (this.secret() || this.retrievingSecret()) return;
    this.retrievingSecret.set(true);
    this.secretError.set(null);
    this.service
      .secret(this.name())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (secret) => {
          this.retrievingSecret.set(false);
          this.secret.set(secret);
        },
        error: (error: unknown) => {
          this.retrievingSecret.set(false);
          this.secretError.set(bucketErrorMessage(error, 'The key pair could not be fetched.'));
        },
      });
  }

  // asks again after a while as long as the bucket is still being counted
  load(name: string): void {
    this.name.set(name);
    this.service
      .bucket(name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (bucket) => {
          this.bucket.set(bucket);
          if (bucket.statistics.computing) {
            timer(POLL_INTERVAL_MS)
              .pipe(takeUntilDestroyed(this.destroyRef))
              .subscribe(() => this.load(name));
          }
        },
        error: (error: unknown) =>
          this.error.set(bucketErrorMessage(error, 'The bucket could not be loaded.')),
      });
  }
}

export function bucketErrorMessage(error: unknown, fallback: string): string {
  return apiErrorMessage(error, fallback, {
    403: 'Your role does not allow to see the buckets.',
    404: 'There is no such bucket.',
  });
}
