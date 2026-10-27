import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { APPS } from '../apps';
import { AuthService } from '../auth/auth.service';

// the index page, a tile for every app
@Component({
  selector: 'app-home',
  imports: [RouterLink],
  styleUrl: './home.scss',
  template: `
    <header class="header">
      <h1 class="title">Apps</h1>
      <span class="user" data-testid="email">{{ auth.email() }}</span>
      <button type="button" class="sign-out" (click)="signOut()">Sign out</button>
    </header>
    <nav aria-label="Apps">
      <ul class="apps">
        @for (app of apps; track app.path) {
          <li>
            <a class="app" [routerLink]="app.path">
              <span class="icon" aria-hidden="true">{{ app.icon }}</span>
              <span class="name">{{ app.name }}</span>
              <span class="description">{{ app.description }}</span>
            </a>
          </li>
        }
      </ul>
    </nav>
  `,
})
export class Home {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly apps = APPS;

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
