import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject, Injectable } from '@angular/core';

export type ColorScheme = 'light' | 'dark';

// tells bootstrap the light or dark mode of the user's browser (data-bs-theme), and follows it
// when it changes; a page that has a mode of its own, the console, pins it while it shows
@Injectable({ providedIn: 'root' })
export class ColorSchemeService {
  private readonly root = inject(DOCUMENT).documentElement;
  private readonly query = matchMedia('(prefers-color-scheme: dark)');
  private pinned: ColorScheme | null = null;

  constructor() {
    const follow = () => this.apply();
    this.query.addEventListener('change', follow);
    inject(DestroyRef).onDestroy(() => this.query.removeEventListener('change', follow));
    this.apply();
  }

  // the mode until unpin(), whatever the browser's is
  pin(scheme: ColorScheme): void {
    this.pinned = scheme;
    this.apply();
  }

  unpin(): void {
    this.pinned = null;
    this.apply();
  }

  private apply(): void {
    this.root.dataset['bsTheme'] = this.pinned ?? (this.query.matches ? 'dark' : 'light');
  }
}
