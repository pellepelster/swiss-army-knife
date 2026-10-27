import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { formatBytes, formatCount } from '../shared/format';
import { Bucket, BucketsService, POLL_INTERVAL_MS, servedDomains } from './buckets.service';

// the buckets with their domains, objects and size; the numbers are counted in the background,
// the list asks again while a bucket is still being counted
@Component({
  selector: 'app-bucket-list',
  imports: [RouterLink],
  styleUrls: ['../shared/app-parts.scss', '../shared/app-page.scss'],
  styles: `
    .buckets {
      display: grid;
      gap: 10px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .bucket {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 4px 16px;
      align-items: baseline;
      color: var(--ink);
      text-decoration: none;

      &:hover,
      &:focus-visible {
        border-color: var(--ink);
      }
    }
    .name {
      font-weight: 600;
      overflow-wrap: anywhere;
    }
    .numbers {
      grid-row: 1 / 3;
      grid-column: 2;
      align-self: center;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    .domains {
      min-width: 0;
      color: var(--muted);
      font-size: 0.9rem;
      overflow-wrap: anywhere;
    }
    .empty {
      text-align: center;
      padding: 32px 16px;
    }
  `,
  template: `
    <header class="toolbar">
      <a class="back" routerLink="/" aria-label="All apps"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="title">Static hosting</h1>
      <a class="button" routerLink="/static-hosting/create">
        <i class="fa-solid fa-plus" aria-hidden="true"></i> Create bucket
      </a>
    </header>

    @if (error(); as error) {
      <p class="error" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <ul class="buckets" aria-label="Buckets">
        @for (bucket of buckets(); track bucket.name) {
          <li>
            <a
              class="bucket sheet"
              [routerLink]="['/static-hosting', bucket.name]"
              [attr.data-testid]="'bucket-' + bucket.name"
            >
              <span class="name mono">{{ bucket.name }}</span>
              <span class="numbers">
                @if (bucket.statistics.failed) {
                  <span class="muted">not counted</span>
                } @else if (bucket.statistics.computing) {
                  <span class="counting">counting…</span>
                } @else {
                  <span data-testid="objects">{{ formatCount(bucket.statistics.objects!) }}</span>
                  <span class="muted"> objects · </span>
                  <span data-testid="bytes">{{ formatBytes(bucket.statistics.bytes!) }}</span>
                }
              </span>
              <span class="domains mono">{{
                served(bucket).join(', ') || 'not served at a domain'
              }}</span>
            </a>
          </li>
        } @empty {
          <li class="empty sheet">
            No buckets yet. A bucket keeps the files of a website, served at its domains.
          </li>
        }
      </ul>
    }
  `,
})
export class BucketList {
  private readonly service = inject(BucketsService);

  protected readonly buckets = signal<Bucket[]>([]);
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;
  protected readonly served = servedDomains;

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
    this.load();
  }

  private load(): void {
    this.subscriptions.add(
      this.service.buckets().subscribe({
        next: (buckets) => {
          this.buckets.set(buckets);
          this.loaded.set(true);
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
