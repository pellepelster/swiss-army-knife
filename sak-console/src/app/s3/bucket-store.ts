import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { Subscription, timer } from 'rxjs';
import { Bucket, BucketsService } from './buckets.service';
import { POLL_INTERVAL_MS } from './buckets';

// the bucket of a detail view, shared by the view and its tabs; provided by the view
@Injectable()
export class BucketStore {
  private readonly service = inject(BucketsService);

  readonly name = signal('');
  readonly bucket = signal<Bucket | null>(null);
  readonly error = signal<string | null>(null);

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
  }

  // asks again after a while as long as the bucket is still being counted
  load(name: string): void {
    this.name.set(name);
    this.subscriptions.add(
      this.service.bucket(name).subscribe({
        next: (bucket) => {
          this.bucket.set(bucket);
          if (bucket.statistics.computing) {
            this.subscriptions.add(timer(POLL_INTERVAL_MS).subscribe(() => this.load(name)));
          }
        },
        error: (error: unknown) => this.error.set(errorMessage(error, 'The bucket could not be loaded.')),
      }),
    );
  }
}

export function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse && error.status === 404) return 'There is no such bucket.';
  if (error instanceof HttpErrorResponse && error.status === 403) return 'Your role does not allow to see the buckets.';
  return fallback;
}
