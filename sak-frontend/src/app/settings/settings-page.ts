import { Component, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  ActivatedRoute,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { apiErrorMessage } from '../shared/api';
import { SettingScope, SettingsService } from './settings.service';

// the scopes of settings, shared by the page and its tabs; provided by the page
@Injectable()
export class SettingScopes {
  readonly scopes = signal<SettingScope[] | null>(null);
}

// a tab for every scope of settings the backend describes, each a child route; without one in the
// url the first is opened
@Component({
  selector: 'app-settings-page',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  providers: [SettingScopes],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        aria-label="All apps"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">Settings</h1>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (store.scopes(); as scopes) {
      <nav class="nav nav-underline mb-3" aria-label="Scopes">
        @for (scope of scopes; track scope.name) {
          <a
            class="nav-link"
            [routerLink]="scope.name"
            routerLinkActive="active"
            ariaCurrentWhenActive="page"
            >{{ scope.label }}</a
          >
        }
      </nav>
      <router-outlet />
    } @else if (!error()) {
      <p class="text-body-secondary">Loading…</p>
    }
  `,
})
export class SettingsPage {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(SettingsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly store = inject(SettingScopes);

  protected readonly error = signal<string | null>(null);

  constructor() {
    this.service
      .scopes()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (scopes) => {
          this.store.scopes.set(scopes);
          if (!this.route.firstChild && scopes.length) {
            void this.router.navigate([scopes[0].name], {
              relativeTo: this.route,
              replaceUrl: true,
            });
          }
        },
        error: (error: unknown) =>
          this.error.set(
            apiErrorMessage(error, 'The settings could not be loaded.', {
              403: 'Your role does not allow to see the settings.',
            }),
          ),
      });
  }
}
