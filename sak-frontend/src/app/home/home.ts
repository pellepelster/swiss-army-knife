import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { APPS } from '../apps';
import { AuthService, UserMode, WhoAmI } from '../auth/auth.service';
import { TextKey, TranslatePipe } from '../i18n/i18n';
import { InstallService } from '../install.service';
import { ActionMenu } from '../shared/action-menu';
import { PageHeader } from '../shared/page-header';

// a card of the maintenance of the backend: a page of the app (path), or a page of its own (href,
// swagger ui); only for an admin unless it is for everyone, the users only in the multi user mode
interface MaintenanceEntry {
  readonly name: TextKey;
  readonly description: TextKey;
  // a font awesome icon
  readonly icon: string;
  readonly path?: string;
  readonly href?: string;
  readonly multiUserOnly?: boolean;
  // for every signed in user, not only an admin
  readonly everyone?: boolean;
}

const MAINTENANCE: readonly MaintenanceEntry[] = [
  {
    name: 'health.title',
    description: 'home.health_description',
    icon: 'fa-heart-pulse',
    path: '/health',
  },
  {
    name: 'shared.settings',
    description: 'home.settings_description',
    icon: 'fa-gear',
    path: '/settings',
  },
  {
    name: 'home.api',
    description: 'home.api_description',
    icon: 'fa-code',
    href: '/swagger/',
  },
  {
    name: 'users.title',
    description: 'home.users_description',
    icon: 'fa-users',
    path: '/users',
    multiUserOnly: true,
  },
  {
    name: 'home.downloads',
    description: 'home.downloads_description',
    icon: 'fa-mobile-screen-button',
    path: '/downloads',
    everyone: true,
  },
];

// the index page, a tile for every app and for the maintenance of the backend; a tile the signed in
// user can not open stays in its place, disabled, and says why (UX-8). The name of the user shows,
// else their address
@Component({
  selector: 'app-home',
  imports: [PageHeader, ActionMenu, RouterLink, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader>
      <h1 class="h3 mb-0 flex-grow-1">{{ 'home.title' | t }}</h1>
      @if (installer.canInstall()) {
        <button
          type="button"
          class="btn btn-primary"
          [attr.aria-label]="'home.install_label' | t"
          (click)="installer.install()"
        >
          <i class="fa-solid fa-download" aria-hidden="true"></i> {{ 'home.install' | t }}
        </button>
      }
      <span class="small text-body-secondary text-truncate" data-testid="username">{{
        whoAmI()?.name ?? whoAmI()?.username
      }}</span>
      <app-action-menu>
        <button type="button" class="dropdown-item" role="menuitem" (click)="signOut()">
          <i class="fa-solid fa-right-from-bracket me-2" aria-hidden="true"></i
          >{{ 'login.sign_out' | t }}
        </button>
      </app-action-menu>
    </header>

    <nav [attr.aria-label]="'home.title' | t">
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
                  <span class="fw-semibold fs-5" data-testid="app-name">{{ app.name | t }}</span>
                  <span class="small text-body-secondary">{{ app.description | t }}</span>
                </span>
              </a>
            } @else {
              <div
                class="card card-body h-100 flex-row align-items-center gap-3 opacity-50"
                aria-disabled="true"
                [title]="'home.only_admin' | t"
              >
                <img class="rounded-3" [src]="app.icon" alt="" width="48" height="48" />
                <span class="vstack">
                  <span class="fw-semibold fs-5" data-testid="app-name">{{ app.name | t }}</span>
                  <span class="small text-body-secondary">{{ 'home.only_admin' | t }}</span>
                </span>
              </div>
            }
          </li>
        }
      </ul>
    </nav>

    <h2 class="h5 mt-4 mb-3">{{ 'home.maintenance' | t }}</h2>
    <nav [attr.aria-label]="'home.maintenance' | t">
      <ul class="row row-cols-1 row-cols-sm-2 row-cols-lg-4 g-3 list-unstyled">
        @for (entry of maintenance; track entry.name) {
          <li class="col">
            @if (unavailable(entry); as reason) {
              <div
                class="card card-body h-100 flex-row align-items-center gap-3 opacity-50"
                aria-disabled="true"
                [title]="reason | t"
              >
                <i class="fa-solid fs-1" [class]="entry.icon" aria-hidden="true"></i>
                <span class="vstack">
                  <span class="fw-semibold fs-5" data-testid="app-name">{{ entry.name | t }}</span>
                  <span class="small text-body-secondary">{{ reason | t }}</span>
                </span>
              </div>
            } @else if (entry.path) {
              <a
                class="card card-body h-100 flex-row align-items-center gap-3 link-body-emphasis text-decoration-none"
                [routerLink]="entry.path"
              >
                <i class="fa-solid fs-1" [class]="entry.icon" aria-hidden="true"></i>
                <span class="vstack">
                  <span class="fw-semibold fs-5" data-testid="app-name">{{ entry.name | t }}</span>
                  <span class="small text-body-secondary">{{ entry.description | t }}</span>
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
                  <span class="fw-semibold fs-5" data-testid="app-name">{{ entry.name | t }}</span>
                  <span class="small text-body-secondary">{{ entry.description | t }}</span>
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
  protected unavailable(entry: MaintenanceEntry): TextKey | null {
    if (!entry.everyone && !this.admin()) return 'home.only_admin';
    if (entry.multiUserOnly && this.userMode() === 'single') return 'home.needs_multi_user';
    return null;
  }

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
