import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  template: `
    <nav class="navbar bg-body-tertiary border-bottom">
      <div class="container">
        <span class="navbar-brand d-flex align-items-center gap-2">
          <img src="favicon.png" alt="" width="16" height="16" />
          sak console
        </span>
      </div>
    </nav>
    <main class="container py-4">
      <router-outlet />
    </main>
  `,
})
export class App {}
