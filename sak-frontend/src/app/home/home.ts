import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { APPS } from '../apps';
import { AuthService } from '../auth/auth.service';
import { InstallService } from '../install.service';

// the index page, a tile for every app and for the maintenance of the backend
@Component({
  selector: 'app-home',
  imports: [RouterLink],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <h1 class="h3 mb-0 flex-grow-1">Apps</h1>
      @if (installer.canInstall()) {
        <button
          type="button"
          class="btn btn-primary"
          aria-label="Install the notes as an app"
          (click)="installer.install()"
        >
          <i class="fa-solid fa-download" aria-hidden="true"></i> Install
        </button>
      }
      <span class="small text-body-secondary text-truncate" data-testid="username">{{ username() }}</span>
      <button type="button" class="btn btn-outline-secondary" (click)="signOut()">Sign out</button>
    </header>
    <nav aria-label="Apps">
      <ul class="row row-cols-1 row-cols-sm-2 row-cols-lg-4 g-3 list-unstyled">
        @for (app of apps; track app.path) {
          <li class="col">
            <a
              class="card card-body h-100 flex-row align-items-center gap-3 link-body-emphasis text-decoration-none"
              [routerLink]="app.path"
            >
              <img class="rounded-3 shadow-sm" [src]="app.icon" alt="" width="48" height="48" />
              <span class="vstack">
                <span class="fw-semibold fs-5" data-testid="app-name">{{ app.name }}</span>
                <span class="small text-body-secondary">{{ app.description }}</span>
              </span>
            </a>
          </li>
        }
      </ul>
    </nav>

    <h2 class="h5 mt-4 mb-3">Maintenance</h2>
    <nav aria-label="Maintenance">
      <ul class="row row-cols-1 row-cols-sm-2 row-cols-lg-4 g-3 list-unstyled">
        <li class="col">
          <a
            class="card card-body h-100 flex-row align-items-center gap-3 link-body-emphasis text-decoration-none"
            routerLink="/health"
          >
            <i class="fa-solid fa-heart-pulse fs-1" aria-hidden="true"></i>
            <span class="vstack">
              <span class="fw-semibold fs-5" data-testid="app-name">Health</span>
              <span class="small text-body-secondary">The status of all health checks</span>
            </span>
          </a>
        </li>
      </ul>
    </nav>
  `,
})
export class Home {
  private readonly destroyRef = inject(DestroyRef);
  protected readonly auth = inject(AuthService);
  protected readonly installer = inject(InstallService);
  private readonly router = inject(Router);

  protected readonly apps = APPS;

  // the signed in address as the backend takes the session, not as the browser stored it
  protected readonly username = signal<string | null>(null);

  constructor() {
    this.auth
      .whoAmI()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((whoAmI) => this.username.set(whoAmI.username));
  }

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
