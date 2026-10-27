import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ColorSchemeService } from '../color-scheme.service';
import { SideBar } from './side-bar/side-bar';

// the stylesheet of the console, bootstrap with the console's theme; only the pages of /console
// have it, the other apps have plain bootstrap
export const CONSOLE_STYLESHEET = 'console.css';

// the frame of the console: the navigation on the left, the page next to it; it shows once the
// stylesheet is loaded and takes it along when the console is left
@Component({
  selector: 'app-console-shell',
  imports: [RouterOutlet, SideBar],
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
    @if (styled()) {
      <div class="shell">
        <app-side-bar />
        <main>
          <router-outlet />
        </main>
      </div>
    }
  `,
})
export class ConsoleShell {
  protected readonly styled = signal(false);

  constructor() {
    // the console is not meant to follow the dark mode of the browser
    const colorScheme = inject(ColorSchemeService);
    colorScheme.pin('light');
    const document = inject(DOCUMENT);
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = CONSOLE_STYLESHEET;
    link.dataset['console'] = '';
    link.onload = () => this.styled.set(true);
    // shown without its look rather than not at all, but told in the browser's console, e.g. for
    // a dev server started before the stylesheet was part of the build
    link.onerror = () => {
      console.error(`the stylesheet of the console (${CONSOLE_STYLESHEET}) could not be loaded`);
      this.styled.set(true);
    };
    document.head.appendChild(link);
    inject(DestroyRef).onDestroy(() => {
      link.remove();
      colorScheme.unpin();
    });
  }
}
