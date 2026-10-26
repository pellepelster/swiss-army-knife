import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AuthService } from './auth/auth.service';
import { SideBar } from './side-bar/side-bar';

@Component({
  imports: [RouterOutlet, SideBar],
  selector: 'app-root',
  styles: `
    .shell {
      --app-topbar-height: 3rem;
      display: flex;
      align-items: stretch;
      height: 100dvh;
      overflow: hidden;
    }

    main {
      flex: 1;
      min-width: 0;
      overflow: auto;
    }

    @media (max-width: 991.98px) {
      app-side-bar ~ main {
        padding-top: var(--app-topbar-height);
      }
    }
  `,
  template: `
    <div class="shell">
      @if (auth.isLoggedIn()) {
        <app-side-bar />
      }
      <main>
        <router-outlet />
      </main>
    </div>
  `,
})
export class App {
  protected readonly auth = inject(AuthService);
}
