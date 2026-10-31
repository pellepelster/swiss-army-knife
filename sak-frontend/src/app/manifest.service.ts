import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject, Injectable } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { innermost } from './favicon.service';

// what the browser installs as an app: its web manifest and the icon of the home screen of an iphone
export interface InstallableApp {
  readonly manifest: string;
  readonly appleTouchIcon: string;
}

// the notes, for every page that names no app of its own, the start page included
export const DEFAULT_APP: InstallableApp = {
  manifest: 'notes.webmanifest',
  appleTouchIcon: 'icons/notes-apple-touch-180.png',
};

// links the manifest of the app a route names in its data (`data: { app }`), the one of the
// innermost route that names one, so each app installs as its own; the server sends every path the
// same page, so it changes after loading
@Injectable({ providedIn: 'root' })
export class ManifestService {
  private readonly document = inject(DOCUMENT);

  constructor() {
    const router = inject(Router);
    const subscription = router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() =>
        this.show(
          innermost<InstallableApp>(router.routerState.snapshot.root, 'app') ?? DEFAULT_APP,
        ),
      );
    inject(DestroyRef).onDestroy(() => subscription.unsubscribe());
  }

  private show(app: InstallableApp): void {
    // the browser reads the manifest again only when the link changes
    if (this.link('manifest').getAttribute('href') !== app.manifest) {
      this.link('manifest').setAttribute('href', app.manifest);
    }
    this.link('apple-touch-icon').setAttribute('href', app.appleTouchIcon);
  }

  // the page's link of the relation, added when the page has none
  private link(rel: string): HTMLLinkElement {
    const existing = this.document.head.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`);
    if (existing) return existing;
    const link = this.document.createElement('link');
    link.rel = rel;
    this.document.head.appendChild(link);
    return link;
  }
}
