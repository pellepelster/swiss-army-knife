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
import { I18n, TranslatePipe } from '../i18n/i18n';
import { PaginationControl } from '../shared/pagination';
import { ActionMenu } from '../shared/action-menu';
import { Bucket, BucketsService, POLL_INTERVAL_MS, servedDomains } from './buckets.service';

// the buckets with their domains, objects and size, a page at a time; the numbers are counted in
// the background, the list asks again while a bucket of the page is still being counted; the menu
// of a row deletes its bucket
@Component({
  selector: 'app-bucket-list',
  imports: [PaginationControl, RouterLink, ActionMenu, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        [attr.aria-label]="'shared.all_apps' | t"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'app.static_hosting' | t }}</h1>
      <a
        class="btn btn-primary"
        routerLink="/static-hosting/create"
        [title]="'hosting.create_page.title' | t"
      >
        <i class="sak-icon-plus" aria-hidden="true"></i> {{ 'shared.create' | t }}
      </a>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <ul class="list-unstyled vstack gap-2 mb-0" [attr.aria-label]="'hosting.list.buckets' | t">
        @for (bucket of buckets(); track bucket.name) {
          <li class="card card-body flex-row align-items-center gap-3">
            <a
              class="flex-grow-1 d-flex justify-content-between gap-3 link-body-emphasis text-decoration-none stretched-link"
              [routerLink]="['/static-hosting', bucket.name]"
              [attr.data-testid]="'bucket-' + bucket.name"
            >
              <span class="vstack gap-1">
                <span class="fw-semibold font-monospace text-break">{{ bucket.name }}</span>
                <span class="small text-body-secondary font-monospace text-break">{{
                  served(bucket).join(', ') || ('hosting.list.not_served' | t)
                }}</span>
              </span>
              <span class="text-end text-nowrap align-self-center">
                @if (bucket.statistics.failed) {
                  <span class="text-body-secondary">{{ 'hosting.not_counted' | t }}</span>
                } @else if (bucket.statistics.computing) {
                  <span class="text-body-secondary fst-italic">{{ 'hosting.counting' | t }}</span>
                } @else {
                  <span data-testid="objects">{{ formatCount(bucket.statistics.objects!) }}</span>
                  <span class="text-body-secondary"> {{ 'hosting.list.objects' | t }} · </span>
                  <span data-testid="bytes">{{ formatBytes(bucket.statistics.bytes!) }}</span>
                }
              </span>
            </a>
            <app-action-menu
              [name]="bucket.name"
              [question]="'hosting.list.delete_question' | t: { name: bucket.name }"
              (deleted)="delete(bucket.name)"
            />
          </li>
        } @empty {
          <li class="card card-body text-center py-4">
            {{ 'hosting.list.empty' | t }}
          </li>
        }
      </ul>
      <app-pagination
        [label]="'hosting.list.pages' | t"
        [pagination]="pagination()"
        (offsetChange)="goTo($event)"
      />
    } @else if (!error()) {
      <p class="text-body-secondary">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class BucketList {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(BucketsService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

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

  // its objects stay, the name stays taken
  protected delete(name: string): void {
    this.service
      .delete(name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.load(this.offset()),
        error: (error: unknown) =>
          this.error.set(
            apiErrorMessage(error, this.i18n.t('hosting.view.delete_failed', { name }), {
              403: this.i18n.t('hosting.view.delete_forbidden'),
            }),
          ),
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
          this.error.set(null);
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
            apiErrorMessage(error, this.i18n.t('hosting.list.load_failed'), {
              403: this.i18n.t('hosting.forbidden'),
            }),
          ),
      });
  }
}
