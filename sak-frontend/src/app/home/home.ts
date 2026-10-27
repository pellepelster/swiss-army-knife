import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ADMINISTRATION, APPS } from '../apps';
import { AuthService } from '../auth/auth.service';
import { InstallService } from '../install.service';

// the index page, a tile for every app and, below them, for the administration
@Component({
  selector: 'app-home',
  imports: [RouterLink],
  styleUrl: './home.scss',
  template: `
    <header class="header">
      <h1 class="title">Apps</h1>
      @if (installer.canInstall()) {
        <button
          type="button"
          class="install"
          aria-label="Install the notes as an app"
          (click)="installer.install()"
        >
          <i class="fa-solid fa-download" aria-hidden="true"></i> Install
        </button>
      }
      <span class="user" data-testid="email">{{ auth.email() }}</span>
      <button type="button" class="sign-out" (click)="signOut()">Sign out</button>
    </header>
    @for (section of sections; track section.name) {
      <nav [attr.aria-label]="section.name">
        @if (section.heading) {
          <h2 class="section-title">{{ section.name }}</h2>
        }
        <ul class="apps">
          @for (app of section.entries; track app.path) {
            <li>
              <a class="app" [routerLink]="app.path">
                <img class="icon" [src]="app.icon" alt="" width="48" height="48" />
                <span class="name">{{ app.name }}</span>
                <span class="description">{{ app.description }}</span>
              </a>
            </li>
          }
        </ul>
      </nav>
    }
  `,
})
export class Home {
  protected readonly auth = inject(AuthService);
  protected readonly installer = inject(InstallService);
  private readonly router = inject(Router);

  // the apps under the title of the page, the administration below them with its own heading
  protected readonly sections = [
    { name: 'Apps', heading: false, entries: APPS },
    { name: 'Administration', heading: true, entries: ADMINISTRATION },
  ];

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
