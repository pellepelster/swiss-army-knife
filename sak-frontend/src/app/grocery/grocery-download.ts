import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AuthService } from '../auth/auth.service';
import { TranslatePipe } from '../i18n/i18n';
import { PAGE_LOADER } from '../service-worker-updates';

// the releases of sak on github: the tag v1.4.2 has the apk sak-grocery-1.4.2.apk, as the updater
// of the app takes it (AndroidUpdater)
export const RELEASES_URL = 'https://github.com/pellepelster/swiss-army-knife/releases';

// the apk of the grocery app of a release
export const apkUrl = (version: string) =>
  `${RELEASES_URL}/download/v${version}/sak-grocery-${version}.apk`;

// /grocery/download/app, without a session: downloads the android app of the version the
// deployment runs from its github release. GitHub answers the apk as a download, the page stays and
// offers it again; a deployment without a release, or one that does not answer, links the releases
@Component({
  selector: 'app-grocery-download',
  imports: [TranslatePipe],
  template: `
    <div class="row min-vh-100 align-items-center justify-content-center py-3">
      <main class="col-12 col-sm-8 col-md-6 col-lg-4">
        <div class="card card-body">
          <h1 class="h4 mb-3 d-flex align-items-center gap-2">
            <img src="icons/grocery.svg" alt="" width="32" height="32" />
            {{ 'grocery.download.title' | t }}
          </h1>
          @if (url(); as url) {
            <p>{{ 'grocery.download.started' | t }}</p>
            <a class="btn btn-primary" [href]="url" data-testid="apk">
              <i class="fa-solid fa-download" aria-hidden="true"></i>
              {{ 'grocery.download.again' | t }}
            </a>
          } @else if (failed(); as failed) {
            <p class="alert alert-danger" role="alert">{{ failed | t }}</p>
            <a [href]="releases">{{ 'grocery.download.releases' | t }}</a>
          } @else {
            <p class="mb-0">{{ 'shared.loading' | t }}</p>
          }
        </div>
      </main>
    </div>
  `,
})
export class GroceryDownload {
  private readonly page = inject(PAGE_LOADER);

  protected readonly releases = RELEASES_URL;
  // the apk once it is known
  protected readonly url = signal<string | null>(null);
  // why there is none
  protected readonly failed = signal<
    'grocery.download.no_release' | 'grocery.download.failed' | null
  >(null);

  constructor() {
    inject(AuthService)
      .info()
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe({
        next: (info) => {
          if (!info.version) {
            this.failed.set('grocery.download.no_release');
            return;
          }
          const url = apkUrl(info.version);
          this.url.set(url);
          this.page.load(url);
        },
        error: () => this.failed.set('grocery.download.failed'),
      });
  }
}
