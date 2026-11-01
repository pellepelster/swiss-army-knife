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
import { Subscription } from 'rxjs';
import { apiErrorMessage, lastPageOffset, Pagination } from '../shared/api';
import { I18n, TextKey, TranslatePipe } from '../i18n/i18n';
import { PaginationControl } from '../shared/pagination';
import { Redirect, RedirectsService, RedirectTarget } from './redirects.service';

const TARGET_TYPES: Record<RedirectTarget['type'], TextKey> = {
  domain: 'redirects.types.domain',
  bucket: 'redirects.types.bucket',
};

// the domains caddy redirects, to an external domain or to the domain a bucket is served at;
// a page at a time, each leads to its page, a redirect is created on a page of its own
@Component({
  selector: 'app-redirect-list',
  imports: [PaginationControl, RouterLink, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        [attr.aria-label]="'redirects.all_apps' | t"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'redirects.title' | t }}</h1>
      <a class="btn btn-primary" routerLink="/redirects/create">
        <i class="sak-icon-plus" aria-hidden="true"></i> {{ 'redirects.create' | t }}
      </a>
    </header>

    <p class="text-body-secondary">
      {{ 'redirects.list.intro' | t }}
    </p>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <ul class="list-unstyled vstack gap-2 mb-0" [attr.aria-label]="'redirects.title' | t">
        @for (redirect of redirects(); track redirect.sourceDomain) {
          <li class="card card-body" [attr.data-testid]="'redirect-' + redirect.sourceDomain">
            <div class="vstack gap-1">
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
          </li>
        } @empty {
          <li class="card card-body text-center py-4">{{ 'redirects.list.empty' | t }}</li>
        }
      </ul>
      <app-pagination
        [label]="'redirects.list.pages' | t"
        [pagination]="pagination()"
        (offsetChange)="goTo($event)"
      />
    } @else if (!error()) {
      <p class="text-body-secondary">{{ 'redirects.loading' | t }}</p>
    }
  `,
})
export class RedirectList {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(RedirectsService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  // the name of the type of a target
  protected readonly TYPES = TARGET_TYPES;

  // the route's offset query parameter, where the page shown starts
  readonly offset = input(0, {
    transform: (value: unknown) => Math.max(0, numberAttribute(value, 0)),
  });

  protected readonly redirects = signal<Redirect[]>([]);
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly pagination = signal<Pagination | null>(null);

  // the loading of the page shown, replaced by the next one
  private loading?: Subscription;

  constructor() {
    effect(() => {
      const offset = this.offset();
      untracked(() => this.load(offset));
    });
  }

  protected goTo(offset: number): void {
    void this.router.navigate(['/redirects'], {
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
          // the page is gone, e.g. its last redirect
          if (page.items.length === 0 && offset > 0) {
            this.goTo(lastPageOffset(page.pagination));
            return;
          }
          this.error.set(null);
          this.redirects.set(page.items);
          this.pagination.set(page.pagination);
          this.loaded.set(true);
        },
        error: (error: unknown) =>
          this.error.set(
            apiErrorMessage(error, this.i18n.t('redirects.list.load_failed'), {
              403: this.i18n.t('redirects.forbidden'),
            }),
          ),
      });
  }
}
