import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  numberAttribute,
  signal,
  untracked,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { lastPageOffset, Pagination } from '../shared/api';
import { formatBytes, formatCount } from '../shared/format';
import { PaginationControl } from '../shared/pagination';
import { Bucket, BucketsService, POLL_INTERVAL_MS, servedDomains } from './buckets.service';

// the buckets with their domains, objects and size, a page at a time; the numbers are counted in
// the background, the list asks again while a bucket of the page is still being counted
@Component({
  selector: 'app-bucket-list',
  imports: [PaginationControl, RouterLink],
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
      <app-pagination
        label="Pages of the buckets"
        [pagination]="pagination()"
        (offsetChange)="goTo($event)"
      />
    }
  `,
})
export class BucketList {
  private readonly service = inject(BucketsService);
  private readonly router = inject(Router);

  // the route's offset query parameter, where the page shown starts
  readonly offset = input(0, {
    transform: (value: unknown) => Math.max(0, numberAttribute(value, 0)),
  });

  protected readonly buckets = signal<Bucket[]>([]);
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly pagination = signal<Pagination | null>(null);

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;
  protected readonly served = servedDomains;

  // the loading of the page shown or the wait before asking again, replaced by the next one
  private loading?: Subscription;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.loading?.unsubscribe());
    effect(() => {
      const offset = this.offset();
      untracked(() => this.load(offset));
    });
  }

  protected goTo(offset: number): void {
    void this.router.navigate(['/static-hosting'], {
      queryParams: { offset: offset > 0 ? offset : null },
    });
  }

  private load(offset: number): void {
    this.loading?.unsubscribe();
    this.loading = this.service.page(offset).subscribe({
      next: (page) => {
        // the page is gone, e.g. its last bucket
        if (page.items.length === 0 && offset > 0) {
          this.goTo(lastPageOffset(page.pagination));
          return;
        }
        this.buckets.set(page.items);
        this.pagination.set(page.pagination);
        this.loaded.set(true);
        if (page.items.some((bucket) => bucket.statistics.computing)) {
          this.loading = timer(POLL_INTERVAL_MS).subscribe(() => this.load(offset));
        }
      },
      error: (error: unknown) =>
        this.error.set(
          error instanceof HttpErrorResponse && error.status === 403
            ? 'Your role does not allow to see the buckets.'
            : 'The buckets could not be loaded.',
        ),
    });
  }
}
