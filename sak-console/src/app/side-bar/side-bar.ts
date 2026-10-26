import { DatePipe } from '@angular/common';
import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../auth/auth.service';

// the navigation rail of the blcks console: the pages on top, the personal menu with the
// sign out at the bottom; below 992px it slides in from a top bar
@Component({
  selector: 'app-side-bar',
  imports: [RouterLink, RouterLinkActive, DatePipe],
  styleUrl: './side-bar.scss',
  host: {
    '(document:keydown.escape)': 'closeAll()',
    '(document:click)': 'onDocumentClick($event)',
  },
  template: `
    <header class="topbar">
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary topbar-toggle"
        [attr.aria-label]="open() ? 'Close navigation' : 'Open navigation'"
        [attr.aria-expanded]="open()"
        aria-controls="side-bar-panel"
        (click)="open.set(!open())"
      >
        <span class="topbar-toggle-bars" aria-hidden="true"></span>
      </button>
      <span class="brand-name">sak<span class="brand-sep">::</span>console</span>
    </header>
    @if (open()) {
      <div class="sidebar-backdrop" (click)="open.set(false)"></div>
    }
    <aside class="sidebar" [class.open]="open()" id="side-bar-panel">
      <div class="brand">
        <img class="brand-icon" src="favicon.png" alt="" width="16" height="16" />
        <span class="brand-name">sak<span class="brand-sep">::</span>console</span>
      </div>

      <nav class="nav-section" aria-label="Navigation">
        <a class="nav-item" routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">
          <span class="nav-square"></span>
          <span class="nav-text">Overview</span>
        </a>
        <div class="label-mono nav-section-label">Logs</div>
        <a class="nav-item" routerLink="/logs/statistics" routerLinkActive="active">
          <span class="nav-square"></span>
          <span class="nav-text">Statistics</span>
        </a>
        <a class="nav-item" routerLink="/logs/live" routerLinkActive="active">
          <span class="nav-square"></span>
          <span class="nav-text">Live</span>
        </a>
        <div class="label-mono nav-section-label">API</div>
        <!-- swagger ui is no page of the console app, it is loaded on its own -->
        <a class="nav-item" href="/swagger/">
          <span class="nav-square"></span>
          <span class="nav-text">Swagger</span>
        </a>
      </nav>

      <div class="sidebar-footer">
        <div class="personal dropup" #personal>
          <button
            type="button"
            class="user-chip"
            aria-haspopup="menu"
            aria-controls="personal-menu"
            [attr.aria-expanded]="menuOpen()"
            (click)="menuOpen.set(!menuOpen())"
            data-testid="personal-menu"
          >
            <span class="avatar">{{ initials() }}</span>
            <span class="user-name font-mono">{{ email() }}</span>
            <span class="caret" aria-hidden="true"></span>
          </button>
          @if (menuOpen()) {
            <div class="dropdown-menu show w-100" id="personal-menu" role="menu">
              <div class="dropdown-header">
                <div class="label-mono">Signed in as</div>
                <div class="font-mono text-body" data-testid="signed-in-as">{{ email() }}</div>
                @if (expiresAt(); as expiresAt) {
                  <div class="small mt-1">Session expires {{ expiresAt | date: 'medium' }}</div>
                }
              </div>
              <div class="dropdown-divider"></div>
              <button type="button" class="dropdown-item" role="menuitem" (click)="logout()">
                Sign out
              </button>
            </div>
          }
        </div>
      </div>
    </aside>
  `,
})
export class SideBar {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  private readonly personal = viewChild.required<ElementRef<HTMLElement>>('personal');

  protected readonly open = signal(false);
  protected readonly menuOpen = signal(false);

  protected readonly email = this.auth.email;
  protected readonly expiresAt = this.auth.expiresAt;
  protected readonly initials = computed(() => initialsOf(this.email()));

  constructor() {
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.closeAll());
  }

  protected closeAll(): void {
    this.open.set(false);
    this.menuOpen.set(false);
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (this.menuOpen() && !this.personal().nativeElement.contains(event.target as Node)) {
      this.menuOpen.set(false);
    }
  }

  protected logout(): void {
    this.closeAll();
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}

export function initialsOf(email: string | null | undefined): string {
  const parts = (email ?? '')
    .split('@')[0]
    .split(/[._+-]+/)
    .filter((part) => part.length > 0);
  if (parts.length === 0) {
    return '?';
  }
  return (parts[0][0] + (parts.length > 1 ? parts[1][0] : '')).toUpperCase();
}
