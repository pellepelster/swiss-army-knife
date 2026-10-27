import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { formatBytes, formatCount } from '../shared/format';
import { Bucket, BucketsService } from './buckets.service';

// how long to wait before asking again while a bucket is still being counted
export const POLL_INTERVAL_MS = 3000;

// the buckets with their number of objects and size; the numbers are counted in the background
// and can be a few minutes old. New buckets are added on their own page.
@Component({
  selector: 'app-buckets',
  imports: [DatePipe, RouterLink],
  template: `
    <div class="page">
      <div class="page-header d-flex flex-wrap align-items-end justify-content-between gap-2">
        <div>
          <div class="label-mono">S3</div>
          <h1 class="mb-1">Buckets</h1>
          <div class="page-subtitle">
            Counted in the background, recounted every few minutes and after every write.
          </div>
        </div>
        <a class="btn btn-primary" routerLink="/s3/buckets/create">Add bucket</a>
      </div>

      @if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      }

      <div class="card">
        <table class="table mb-0" aria-label="Buckets">
          <thead>
            <tr>
              <th scope="col">Bucket</th>
              <th scope="col">Domains</th>
              <th scope="col" class="text-end">Objects</th>
              <th scope="col" class="text-end">Size</th>
              <th scope="col">Counted</th>
            </tr>
          </thead>
          <tbody>
            @for (bucket of buckets(); track bucket.name) {
              <tr [attr.data-testid]="'bucket-' + bucket.name">
                <td class="font-mono">
                  <a [routerLink]="['/s3/buckets', bucket.name]">{{ bucket.name }}</a>
                </td>
                <td class="font-mono">{{ bucket.domains.join(', ') || '–' }}</td>
                @if (bucket.statistics.failed) {
                  <td colspan="3" class="text-secondary">could not be counted</td>
                } @else if (bucket.statistics.computing) {
                  <td colspan="3" class="text-secondary">
                    <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                    counting…
                  </td>
                } @else {
                  <td class="font-mono text-end" data-testid="objects">{{ formatCount(bucket.statistics.objects!) }}</td>
                  <td class="font-mono text-end" data-testid="bytes">{{ formatBytes(bucket.statistics.bytes!) }}</td>
                  <td class="text-secondary small">{{ bucket.statistics.computedAt | date: 'HH:mm:ss' }}</td>
                }
              </tr>
            } @empty {
              <tr>
                <td colspan="5" class="text-center text-secondary py-4">No buckets.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  `,
})
export class Buckets {
  private readonly service = inject(BucketsService);

  protected readonly buckets = signal<Bucket[]>([]);
  protected readonly error = signal<string | null>(null);

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
    this.load();
  }

  // asks again after a while as long as a bucket is still being counted
  private load(): void {
    this.subscriptions.add(
      this.service.buckets().subscribe({
        next: (buckets) => {
          this.buckets.set(buckets);
          if (buckets.some((bucket) => bucket.statistics.computing)) {
            this.subscriptions.add(timer(POLL_INTERVAL_MS).subscribe(() => this.load()));
          }
        },
        error: (error: unknown) =>
          this.error.set(
            error instanceof HttpErrorResponse && error.status === 403
              ? 'Your role does not allow to see the buckets.'
              : 'The buckets could not be loaded.',
          ),
      }),
    );
  }
}
