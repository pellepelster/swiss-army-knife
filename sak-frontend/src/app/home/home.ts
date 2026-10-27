import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { APPS } from '../apps';

// the index page, a tile for every app
@Component({
  selector: 'app-home',
  imports: [RouterLink],
  styleUrl: './home.scss',
  template: `
    <header class="header">
      <h1 class="title">Apps</h1>
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
  protected readonly apps = APPS;
}
