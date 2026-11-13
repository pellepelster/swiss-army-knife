import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { ConfirmDelete } from '../shared/confirm-delete';
import { LocalDateTimePipe } from '../shared/local-date-time.pipe';
import { RedirectStore, redirectDeleteErrorMessage } from './redirect-store';
import { RedirectsService } from './redirects.service';
import { PageHeader } from '../shared/page-header';

// one redirect: its overview on top, its details in the tabs below, the first one its
// configuration
@Component({
  selector: 'app-redirect-view',
  imports: [
    PageHeader,
    ConfirmDelete,
    LocalDateTimePipe,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    TranslatePipe,
  ],
  providers: [RedirectStore],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/redirects" [backLabel]="'redirects.all_redirects' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break font-monospace">{{ source() }}</h1>
      @if (store.redirect()) {
        <!-- one group, the delete leftmost; it asks in its place, Keep is the safe default -->
        @if (confirming()) {
          <app-confirm-delete
            [name]="source()"
            (confirmed)="delete()"
            (kept)="confirming.set(false)"
          />
        } @else {
          <div class="btn-group" role="group" [attr.aria-label]="'shared.actions' | t">
            <button
              class="btn btn-outline-danger"
              type="button"
              [attr.aria-label]="'redirects.view.delete_redirect' | t: { domain: source() }"
              (click)="confirming.set(true)"
            >
              <i class="fa-solid fa-trash" aria-hidden="true"></i> {{ 'shared.delete' | t }}
            </button>
            <a class="btn btn-primary" [routerLink]="['/redirects', source(), 'edit']">
              <i class="fa-solid fa-pen" aria-hidden="true"></i> {{ 'shared.edit' | t }}
            </a>
          </div>
        }
      }
    </header>

    @if (store.error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (store.redirect(); as redirect) {
      <dl
        class="card card-body flex-row flex-wrap gap-4 mb-3"
        [attr.aria-label]="'shared.view.overview' | t"
      >
        <div>
          <dt class="small text-uppercase text-body-secondary fw-normal">
            {{ 'shared.view.created' | t }}
          </dt>
          <dd class="mb-0 fw-semibold" data-testid="created">
            {{ redirect.createdAt | localDateTime: 'medium' : 'short' }}
          </dd>
        </div>
        @if (redirect.updatedAt; as updatedAt) {
          <div>
            <dt class="small text-uppercase text-body-secondary fw-normal">
              {{ 'shared.view.updated' | t }}
            </dt>
            <dd class="mb-0 fw-semibold" data-testid="updated">
              {{ updatedAt | localDateTime: 'medium' : 'short' }}
            </dd>
          </div>
        }
        <div>
          <dt class="small text-uppercase text-body-secondary fw-normal">
            {{ 'redirects.view.leads_to' | t }}
          </dt>
          <dd class="mb-0 fw-semibold font-monospace text-break" data-testid="location">
            @if (redirect.location) {
              https://{{ redirect.location }}
            } @else {
              <span class="text-body-secondary">{{ 'redirects.view.nowhere' | t }}</span>
            }
          </dd>
        </div>
      </dl>

      <nav class="nav nav-tabs" [attr.aria-label]="'shared.view.details' | t">
        <a
          class="nav-link"
          routerLink="configuration"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          >{{ 'shared.view.configuration' | t }}</a
        >
      </nav>
      <router-outlet />
    }
  `,
})
export class RedirectView {
  private readonly destroyRef = inject(DestroyRef);
  protected readonly store = inject(RedirectStore);
  private readonly service = inject(RedirectsService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  // the route parameter
  readonly source = input.required<string>();

  protected readonly confirming = signal(false);

  constructor() {
    // loads again for another route
    effect(() => {
      const source = this.source();
      untracked(() => this.store.load(source));
    });
  }

  protected delete(): void {
    this.confirming.set(false);
    this.service
      .delete(this.source())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigate(['/redirects']),
        error: (error: unknown) =>
          this.store.error.set(redirectDeleteErrorMessage(this.i18n, error, this.source())),
      });
  }
}
