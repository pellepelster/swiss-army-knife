import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject, Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

// the favicon of a page as svg and as png for the browsers without svg favicons
export interface Favicon {
  readonly svg: string;
  readonly png: string;
}

// the swiss army knife of the app list, for every page that names no favicon of its own
export const DEFAULT_FAVICON: Favicon = { svg: 'favicon.svg', png: 'favicon-32.png' };

// shows the favicon a route names in its data (`data: { favicon }`), the one of the innermost
// route that names one; the server sends every path the same page, so it changes after loading
@Injectable({ providedIn: 'root' })
export class FaviconService {
  private readonly document = inject(DOCUMENT);

  constructor() {
    const router = inject(Router);
    const subscription = router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() => this.show(favicon(router.routerState.snapshot.root) ?? DEFAULT_FAVICON));
    inject(DestroyRef).onDestroy(() => subscription.unsubscribe());
  }

  private show(icon: Favicon): void {
    this.link('image/svg+xml').setAttribute('href', icon.svg);
    this.link('image/png').setAttribute('href', icon.png);
  }

  // the page's favicon link of the type, added when the page has none
  private link(type: string): HTMLLinkElement {
    const existing = this.document.head.querySelector<HTMLLinkElement>(
      `link[rel="icon"][type="${type}"]`,
    );
    if (existing) return existing;
    const link = this.document.createElement('link');
    link.rel = 'icon';
    link.type = type;
    this.document.head.appendChild(link);
    return link;
  }
}

function favicon(route: ActivatedRouteSnapshot): Favicon | undefined {
  const inner = route.firstChild ? favicon(route.firstChild) : undefined;
  return inner ?? (route.data['favicon'] as Favicon | undefined);
}
