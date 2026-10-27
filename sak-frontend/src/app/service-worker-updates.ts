import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject, Injectable, InjectionToken } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationStart, Router } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';
import { filter } from 'rxjs';

// loads the page anew, the app as the service worker serves it now
export interface PageLoader {
  reload(): void;
  load(url: string): void;
}

export const PAGE_LOADER = new InjectionToken<PageLoader>('PageLoader', {
  providedIn: 'root',
  factory: () => {
    const location = inject(DOCUMENT).location;
    return { reload: () => location.reload(), load: (url) => location.assign(url) };
  },
});

// keeps the app and its service worker in step: a worker that can no longer serve the version the
// page runs reloads the page; a new version, once the worker has it, takes over at the next
// navigation, so nothing being typed is lost; the app looks for a new version whenever it comes
// back into view, a phone keeps it open for days. Nothing happens without a service worker, e.g.
// in development
@Injectable({ providedIn: 'root' })
export class ServiceWorkerUpdates {
  private readonly updates = inject(SwUpdate);
  private readonly router = inject(Router);
  private readonly page = inject(PAGE_LOADER);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  // a new version is ready, the next navigation loads it
  private versionReady = false;

  start(): void {
    if (!this.updates.isEnabled) return;

    // e.g. files of the version evicted from the cache, or changed on the server under its hash
    this.updates.unrecoverable
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.page.reload());

    this.updates.versionUpdates
      .pipe(
        filter((event) => event.type === 'VERSION_READY'),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => (this.versionReady = true));

    this.router.events
      .pipe(
        filter((event): event is NavigationStart => event instanceof NavigationStart),
        filter(() => this.versionReady),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((event) => void this.loadNewVersion(event.url));

    const visible = () => {
      // the next check comes anyway, e.g. without the network
      if (this.document.visibilityState === 'visible')
        this.updates.checkForUpdate().catch(() => false);
    };
    this.document.addEventListener('visibilitychange', visible);
    this.destroyRef.onDestroy(() => this.document.removeEventListener('visibilitychange', visible));
  }

  // the page of the navigation in the new version
  private async loadNewVersion(url: string): Promise<void> {
    this.versionReady = false;
    try {
      await this.updates.activateUpdate();
    } finally {
      this.page.load(url);
    }
  }
}
