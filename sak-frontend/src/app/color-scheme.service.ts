import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject, Injectable } from '@angular/core';

type ColorScheme = 'light' | 'dark';

// tells bootstrap the light or dark mode of the user's browser (data-bs-theme), and follows it
// when it changes
@Injectable({ providedIn: 'root' })
export class ColorSchemeService {
  private readonly root = inject(DOCUMENT).documentElement;
  private readonly query = matchMedia('(prefers-color-scheme: dark)');

  constructor() {
    const follow = () => this.apply();
    this.query.addEventListener('change', follow);
    inject(DestroyRef).onDestroy(() => this.query.removeEventListener('change', follow));
    this.apply();
  }

  private apply(): void {
    this.root.dataset['bsTheme'] = this.query.matches ? 'dark' : 'light';
  }
}
