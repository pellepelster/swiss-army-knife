import { Component, DestroyRef, inject, input } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { apiErrorMessage } from '../shared/api';
import { formatBytes, formatCount } from '../shared/format';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { PaginationControl } from '../shared/pagination';
import { pagedList, pageOffset } from '../shared/paged-list';
import { ActionMenu } from '../shared/action-menu';
import { BucketsService, POLL_INTERVAL_MS, servedDomains } from './buckets.service';
import { PageHeader } from '../shared/page-header';

// the buckets with their domains, objects and size, a page at a time; the numbers are counted in
// the background, the list asks again while a bucket of the page is still being counted; the menu
// of a row deletes its bucket
@Component({
  selector: 'app-bucket-list',
  imports: [PageHeader, PaginationControl, RouterLink, ActionMenu, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'app.static_hosting' | t }}</h1>
      <a
        class="btn btn-primary"
        routerLink="/static-hosting/create"
        [title]="'hosting.create_page.title' | t"
      >
        <i class="sak-icon-plus" aria-hidden="true"></i> {{ 'shared.create' | t }}
      </a>
    </header>

    @if (list.error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (list.loaded()) {
      <ul class="list-unstyled vstack gap-2 mb-0" [attr.aria-label]="'hosting.list.buckets' | t">
        @for (bucket of list.items(); track bucket.name) {
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
            <app-action-menu [name]="bucket.name" deletes (deleted)="delete(bucket.name)">
              <a
                class="dropdown-item"
                role="menuitem"
                [routerLink]="['/static-hosting', bucket.name, 'edit']"
                ><i class="fa-solid fa-pen me-2" aria-hidden="true"></i>{{ 'shared.edit' | t }}</a
              >
            </app-action-menu>
          </li>
        } @empty {
          <li class="card card-body text-center py-4">
            {{ 'hosting.list.empty' | t }}
          </li>
        }
      </ul>
      <app-pagination
        [label]="'hosting.list.pages' | t"
        [pagination]="list.pagination()"
        (offsetChange)="list.goTo($event)"
      />
    } @else if (!list.error()) {
      <p class="text-body-secondary">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class BucketList {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(BucketsService);
  private readonly i18n = inject(I18n);

  // the route's offset query parameter, where the page shown starts
  readonly offset = input(0, { transform: pageOffset });

  protected readonly list = pagedList({
    path: '/static-hosting',
    offset: this.offset,
    page: (offset) => this.service.page(offset),
    error: (error) =>
      apiErrorMessage(error, this.i18n.t('hosting.list.load_failed'), {
        403: this.i18n.t('hosting.forbidden'),
      }),
    again: {
      while: (buckets) => buckets.some((bucket) => bucket.statistics.computing),
      ms: POLL_INTERVAL_MS,
    },
  });

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;
  protected readonly served = servedDomains;

  // its objects stay, the name stays taken
  protected delete(name: string): void {
    this.service
      .delete(name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.list.reload(),
        error: (error: unknown) =>
          this.list.error.set(
            apiErrorMessage(error, this.i18n.t('hosting.view.delete_failed', { name }), {
              403: this.i18n.t('hosting.view.delete_forbidden'),
            }),
          ),
      });
  }
}
