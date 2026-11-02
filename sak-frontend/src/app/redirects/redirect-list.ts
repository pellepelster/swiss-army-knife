import { Component, DestroyRef, inject, input } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { apiErrorMessage } from '../shared/api';
import { I18n, TextKey, TranslatePipe } from '../i18n/i18n';
import { PaginationControl } from '../shared/pagination';
import { pagedList, pageOffset } from '../shared/paged-list';
import { ActionMenu } from '../shared/action-menu';
import { redirectDeleteErrorMessage } from './redirect-store';
import { RedirectsService, RedirectTarget } from './redirects.service';
import { PageHeader } from '../shared/page-header';

const TARGET_TYPES: Record<RedirectTarget['type'], TextKey> = {
  domain: 'redirects.types.domain',
  bucket: 'redirects.types.bucket',
};

// the domains caddy redirects, to an external domain or to the domain a bucket is served at;
// a page at a time, each leads to its page and its menu deletes it, a redirect is created on a
// page of its own
@Component({
  selector: 'app-redirect-list',
  imports: [PageHeader, PaginationControl, RouterLink, ActionMenu, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'app.redirects' | t }}</h1>
      <a class="btn btn-primary" routerLink="/redirects/create" [title]="'redirects.create' | t">
        <i class="sak-icon-plus" aria-hidden="true"></i> {{ 'redirects.create' | t }}
      </a>
    </header>

    <p class="text-body-secondary">
      {{ 'redirects.list.intro' | t }}
    </p>

    @if (list.error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (list.loaded()) {
      <ul class="list-unstyled vstack gap-2 mb-0" [attr.aria-label]="'app.redirects' | t">
        @for (redirect of list.items(); track redirect.sourceDomain) {
          <li
            class="card card-body flex-row align-items-center gap-3"
            [attr.data-testid]="'redirect-' + redirect.sourceDomain"
          >
            <div class="vstack gap-1 flex-grow-1">
              <a
                class="fw-semibold font-monospace text-break"
                [routerLink]="['/redirects', redirect.sourceDomain]"
                >{{ redirect.sourceDomain }}</a
              >
              <span class="target small text-body-secondary">
                <i class="fa-solid fa-arrow-right" aria-hidden="true"></i>
                <span class="badge border text-body-secondary text-uppercase fw-normal mx-1">{{
                  TYPES[redirect.target.type] | t
                }}</span>
                @if (redirect.target.type === 'bucket') {
                  <a
                    class="font-monospace text-break"
                    [routerLink]="['/static-hosting', redirect.target.bucket]"
                    >{{ redirect.target.bucket }}</a
                  >
                } @else {
                  <span class="font-monospace text-break">{{ redirect.target.domain }}</span>
                }
              </span>
            </div>
            <app-action-menu
              [name]="redirect.sourceDomain"
              deletes
              (deleted)="delete(redirect.sourceDomain)"
            >
              <a
                class="dropdown-item"
                role="menuitem"
                [routerLink]="['/redirects', redirect.sourceDomain, 'edit']"
                ><i class="fa-solid fa-pen me-2" aria-hidden="true"></i>{{ 'shared.edit' | t }}</a
              >
            </app-action-menu>
          </li>
        } @empty {
          <li class="card card-body text-center py-4">{{ 'redirects.list.empty' | t }}</li>
        }
      </ul>
      <app-pagination
        [label]="'redirects.list.pages' | t"
        [pagination]="list.pagination()"
        (offsetChange)="list.goTo($event)"
      />
    } @else if (!list.error()) {
      <p class="text-body-secondary">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class RedirectList {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(RedirectsService);
  private readonly i18n = inject(I18n);

  // the name of the type of a target
  protected readonly TYPES = TARGET_TYPES;

  // the route's offset query parameter, where the page shown starts
  readonly offset = input(0, { transform: pageOffset });

  protected readonly list = pagedList({
    path: '/redirects',
    offset: this.offset,
    page: (offset) => this.service.page(offset),
    error: (error) =>
      apiErrorMessage(error, this.i18n.t('redirects.list.load_failed'), {
        403: this.i18n.t('redirects.forbidden'),
      }),
  });

  protected delete(domain: string): void {
    this.service
      .delete(domain)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.list.reload(),
        error: (error: unknown) =>
          this.list.error.set(redirectDeleteErrorMessage(this.i18n, error, domain)),
      });
  }
}
