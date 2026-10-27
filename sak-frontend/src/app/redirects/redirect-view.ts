import { Component, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { LocalDateTimePipe } from '../shared/local-date-time.pipe';
import { RedirectStore } from './redirect-store';
import { RedirectsService } from './redirects.service';

// one redirect: its overview on top, its details in the tabs below, the first one its
// configuration; it is edited on a page of its own and deleted after a second click
@Component({
  selector: 'app-redirect-view',
  imports: [LocalDateTimePipe, RouterLink, RouterLinkActive, RouterOutlet],
  providers: [RedirectStore],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/redirects"
        aria-label="All redirects"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break font-monospace">{{ source() }}</h1>
      @if (store.redirect()) {
        @if (confirming()) {
          <button class="btn btn-danger" type="button" (click)="delete()">Delete</button>
          <button class="btn btn-outline-secondary" type="button" (click)="confirming.set(false)">
            Keep
          </button>
        } @else {
          <a class="btn btn-primary" [routerLink]="['/redirects', source(), 'edit']">
            <i class="fa-solid fa-pen" aria-hidden="true"></i> Edit
          </a>
          <button
            class="btn btn-outline-secondary"
            type="button"
            [attr.aria-label]="'Delete the redirect of ' + source()"
            (click)="confirming.set(true)"
          >
            <i class="fa-solid fa-trash" aria-hidden="true"></i> Delete
          </button>
        }
      }
    </header>

    @if (store.error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (store.redirect(); as redirect) {
      <dl class="card card-body flex-row flex-wrap gap-4 mb-3" aria-label="Overview">
        <div>
          <dt class="small text-uppercase text-body-secondary fw-normal">Created</dt>
          <dd class="mb-0 fw-semibold" data-testid="created">
            {{ redirect.createdAt | localDateTime: 'medium' : 'short' }}
          </dd>
        </div>
        @if (redirect.updatedAt; as updatedAt) {
          <div>
            <dt class="small text-uppercase text-body-secondary fw-normal">Updated</dt>
            <dd class="mb-0 fw-semibold" data-testid="updated">
              {{ updatedAt | localDateTime: 'medium' : 'short' }}
            </dd>
          </div>
        }
        <div>
          <dt class="small text-uppercase text-body-secondary fw-normal">Leads to</dt>
          <dd class="mb-0 fw-semibold font-monospace text-break" data-testid="location">
            @if (redirect.location) {
              https://{{ redirect.location }}
            } @else {
              <span class="text-body-secondary">nowhere</span>
            }
          </dd>
        </div>
      </dl>

      <nav class="nav nav-underline mb-3" aria-label="Details">
        <a
          class="nav-link"
          routerLink="configuration"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          >Configuration</a
        >
      </nav>
      <router-outlet />
    }
  `,
})
export class RedirectView implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  protected readonly store = inject(RedirectStore);
  private readonly service = inject(RedirectsService);
  private readonly router = inject(Router);

  // the route parameter
  readonly source = input.required<string>();

  protected readonly confirming = signal(false);

  ngOnInit(): void {
    this.store.load(this.source());
  }

  protected delete(): void {
    this.confirming.set(false);
    this.service
      .delete(this.source())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigate(['/redirects']),
        error: () => this.store.error.set(`The redirect of ${this.source()} could not be deleted.`),
      });
  }
}
