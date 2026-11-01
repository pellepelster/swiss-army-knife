import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { APPS } from '../apps';
import { AuthService, UserMode, WhoAmI } from '../auth/auth.service';
import { InstallService } from '../install.service';

// a card of the maintenance of the backend: a page of the app (path), or a page of its own (href,
// swagger ui); only for an admin, the users only in the multi user mode
interface MaintenanceEntry {
  readonly name: string;
  readonly description: string;
  // a font awesome icon
  readonly icon: string;
  readonly path?: string;
  readonly href?: string;
  readonly multiUserOnly?: boolean;
}

const MAINTENANCE: readonly MaintenanceEntry[] = [
  {
    name: 'Health',
    description: 'The status of all health checks',
    icon: 'fa-heart-pulse',
    path: '/health',
  },
  {
    name: 'Settings',
    description: 'What the whole deployment does',
    icon: 'fa-gear',
    path: '/settings',
  },
  {
    name: 'API',
    description: 'The full api of the backend, to try out',
    icon: 'fa-code',
    href: '/swagger/',
  },
  {
    name: 'Users',
    description: 'Who may sign in besides the admins',
    icon: 'fa-users',
    path: '/users',
    multiUserOnly: true,
  },
];

// the index page, a tile for every app and for the maintenance of the backend; a tile the signed in
// user can not open stays in its place, disabled, and says why (UX-8). The name of the user shows,
// else their address
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
      <span class="small text-body-secondary text-truncate" data-testid="username">{{
        whoAmI()?.name ?? whoAmI()?.username
      }}</span>
      <button type="button" class="btn btn-outline-secondary" (click)="signOut()">Sign out</button>
    </header>
    <nav aria-label="Apps">
      <ul class="row row-cols-1 row-cols-sm-2 row-cols-lg-4 g-3 list-unstyled">
        @for (app of apps; track app.path) {
          <li class="col">
            @if (!app.adminOnly || admin()) {
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
            } @else {
              <div
                class="card card-body h-100 flex-row align-items-center gap-3 opacity-50"
                aria-disabled="true"
                title="Only for an admin"
              >
                <img class="rounded-3" [src]="app.icon" alt="" width="48" height="48" />
                <span class="vstack">
                  <span class="fw-semibold fs-5" data-testid="app-name">{{ app.name }}</span>
                  <span class="small text-body-secondary">Only for an admin</span>
                </span>
              </div>
            }
          </li>
        }
      </ul>
    </nav>

    <h2 class="h5 mt-4 mb-3">Maintenance</h2>
    <nav aria-label="Maintenance">
      <ul class="row row-cols-1 row-cols-sm-2 row-cols-lg-4 g-3 list-unstyled">
        @for (entry of maintenance; track entry.name) {
          <li class="col">
            @if (unavailable(entry); as reason) {
              <div
                class="card card-body h-100 flex-row align-items-center gap-3 opacity-50"
                aria-disabled="true"
                [title]="reason"
              >
                <i class="fa-solid fs-1" [class]="entry.icon" aria-hidden="true"></i>
                <span class="vstack">
                  <span class="fw-semibold fs-5" data-testid="app-name">{{ entry.name }}</span>
                  <span class="small text-body-secondary">{{ reason }}</span>
                </span>
              </div>
            } @else if (entry.path) {
              <a
                class="card card-body h-100 flex-row align-items-center gap-3 link-body-emphasis text-decoration-none"
                [routerLink]="entry.path"
              >
                <i class="fa-solid fs-1" [class]="entry.icon" aria-hidden="true"></i>
                <span class="vstack">
                  <span class="fw-semibold fs-5" data-testid="app-name">{{ entry.name }}</span>
                  <span class="small text-body-secondary">{{ entry.description }}</span>
                </span>
              </a>
            } @else {
              <!-- a page of its own next to the app, no route of it -->
              <a
                class="card card-body h-100 flex-row align-items-center gap-3 link-body-emphasis text-decoration-none"
                [href]="entry.href"
              >
                <i class="fa-solid fs-1" [class]="entry.icon" aria-hidden="true"></i>
                <span class="vstack">
                  <span class="fw-semibold fs-5" data-testid="app-name">{{ entry.name }}</span>
                  <span class="small text-body-secondary">{{ entry.description }}</span>
                </span>
              </a>
            }
          </li>
        }
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
  protected readonly maintenance = MAINTENANCE;

  // the signed in user as the backend takes the session, not as the browser stored it
  protected readonly whoAmI = signal<WhoAmI | null>(null);
  protected readonly userMode = signal<UserMode | null>(null);
  // until the backend answered, everything shows as it does for an admin
  protected readonly admin = computed(() => this.whoAmI()?.roles.includes('admin') ?? true);

  constructor() {
    this.auth
      .whoAmI()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((whoAmI) => this.whoAmI.set(whoAmI));
    this.auth
      .userMode()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((userMode) => this.userMode.set(userMode));
  }

  // why the user can not open the entry, null when they can
  protected unavailable(entry: MaintenanceEntry): string | null {
    if (!this.admin()) return 'Only for an admin';
    if (entry.multiUserOnly && this.userMode() === 'single') return 'Needs the multi user mode';
    return null;
  }

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
