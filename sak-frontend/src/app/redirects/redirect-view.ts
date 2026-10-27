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
  styleUrls: ['../shared/app-page.scss', '../shared/detail-view.scss'],
  styles: `
    .button-danger {
      background: var(--danger);
      color: var(--sheet);
    }
  `,
  template: `
    <header class="toolbar">
      <a class="back" routerLink="/redirects" aria-label="All redirects"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="title mono">{{ source() }}</h1>
      @if (store.redirect()) {
        @if (confirming()) {
          <button class="button button-danger" type="button" (click)="delete()">Delete</button>
          <button class="button button-quiet" type="button" (click)="confirming.set(false)">
            Keep
          </button>
        } @else {
          <a class="button" [routerLink]="['/redirects', source(), 'edit']">
            <i class="fa-solid fa-pen" aria-hidden="true"></i> Edit
          </a>
          <button
            class="button button-quiet"
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
      <p class="error" role="alert">{{ error }}</p>
    }

    @if (store.redirect(); as redirect) {
      <dl class="overview sheet" aria-label="Overview">
        <div>
          <dt>Created</dt>
          <dd data-testid="created">
            {{ redirect.createdAt | localDateTime: 'medium' : 'short' }}
          </dd>
        </div>
        @if (redirect.updatedAt; as updatedAt) {
          <div>
            <dt>Updated</dt>
            <dd data-testid="updated">{{ updatedAt | localDateTime: 'medium' : 'short' }}</dd>
          </div>
        }
        <div>
          <dt>Leads to</dt>
          <dd class="mono" data-testid="location">
            @if (redirect.location) {
              https://{{ redirect.location }}
            } @else {
              <span class="muted">nowhere</span>
            }
          </dd>
        </div>
      </dl>

      <nav class="tabs" aria-label="Details">
        <a
          class="tab"
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
