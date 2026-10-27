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
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { apiErrorMessage, lastPageOffset, Pagination } from '../shared/api';
import { formatBytes, formatCount } from '../shared/format';
import { PaginationControl } from '../shared/pagination';
import { Bucket, BucketsService, POLL_INTERVAL_MS, servedDomains } from './buckets.service';

// the buckets with their domains, objects and size, a page at a time; the numbers are counted in
// the background, the list asks again while a bucket of the page is still being counted
@Component({
  selector: 'app-bucket-list',
  imports: [PaginationControl, RouterLink],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        aria-label="All apps"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">Static hosting</h1>
      <a class="btn btn-primary" routerLink="/static-hosting/create">
        <i class="fa-solid fa-plus" aria-hidden="true"></i> Create bucket
      </a>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <ul class="list-unstyled vstack gap-2 mb-0" aria-label="Buckets">
        @for (bucket of buckets(); track bucket.name) {
          <li>
            <a
              class="card card-body flex-row justify-content-between gap-3 link-body-emphasis text-decoration-none"
              [routerLink]="['/static-hosting', bucket.name]"
              [attr.data-testid]="'bucket-' + bucket.name"
            >
              <span class="vstack gap-1">
                <span class="fw-semibold font-monospace text-break">{{ bucket.name }}</span>
                <span class="small text-body-secondary font-monospace text-break">{{
                  served(bucket).join(', ') || 'not served at a domain'
                }}</span>
              </span>
              <span class="text-end text-nowrap align-self-center">
                @if (bucket.statistics.failed) {
                  <span class="text-body-secondary">not counted</span>
                } @else if (bucket.statistics.computing) {
                  <span class="text-body-secondary fst-italic">counting…</span>
                } @else {
                  <span data-testid="objects">{{ formatCount(bucket.statistics.objects!) }}</span>
                  <span class="text-body-secondary"> objects · </span>
                  <span data-testid="bytes">{{ formatBytes(bucket.statistics.bytes!) }}</span>
                }
              </span>
            </a>
          </li>
        } @empty {
          <li class="card card-body text-center py-4">
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
  private readonly destroyRef = inject(DestroyRef);
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
    this.loading = this.service
      .page(offset)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
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
            this.loading = timer(POLL_INTERVAL_MS)
              .pipe(takeUntilDestroyed(this.destroyRef))
              .subscribe(() => this.load(offset));
          }
        },
        error: (error: unknown) =>
          this.error.set(
            apiErrorMessage(error, 'The buckets could not be loaded.', {
              403: 'Your role does not allow to see the buckets.',
            }),
          ),
      });
  }
}
