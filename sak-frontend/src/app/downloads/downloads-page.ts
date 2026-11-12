import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { APPS } from '../apps';
import { AuthService } from '../auth/auth.service';
import { TextKey, TranslatePipe } from '../i18n/i18n';
import { AndroidApp, apkUrl, RELEASES_URL } from '../shared/app-download';
import { PageHeader } from '../shared/page-header';

// the apps that have an android app, in the order of the index page
const ANDROID_APPS: readonly AndroidApp[] = ['notes', 'grocery', 'todo'];

// /downloads, without a session so its link opens on a phone: a download link of each android app
// in the version the SAK instance runs, from its github release; a SAK instance without a release,
// or one that does not answer, links the releases
@Component({
  selector: 'app-downloads-page',
  imports: [PageHeader, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1">{{ 'downloads.title' | t }}</h1>
    </header>

    <p>{{ 'downloads.intro' | t }}</p>

    @if (failed(); as failed) {
      <p class="alert alert-danger" role="alert">{{ failed | t }}</p>
      <a [href]="releases">{{ 'app_download.releases' | t }}</a>
    } @else if (version(); as version) {
      <ul class="row row-cols-1 row-cols-sm-2 g-3 list-unstyled">
        @for (app of apps; track app.id) {
          <li class="col">
            <div class="card card-body h-100 flex-row align-items-center gap-3">
              <img class="rounded-3 shadow-sm" [src]="app.icon" alt="" width="48" height="48" />
              <span class="vstack">
                <span class="fw-semibold fs-5" data-testid="app-name">{{ app.name | t }}</span>
                <span class="small text-body-secondary">{{
                  'downloads.version' | t: { version }
                }}</span>
              </span>
              <a class="btn btn-outline-secondary" [href]="url(app.id, version)" data-testid="apk">
                <i class="fa-solid fa-download" aria-hidden="true"></i>
                {{ 'downloads.download' | t: { app: (app.name | t) } }}
              </a>
            </div>
          </li>
        }
      </ul>
    } @else {
      <p class="mb-0">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class DownloadsPage {
  protected readonly releases = RELEASES_URL;
  protected readonly apps = ANDROID_APPS.map((id) => {
    const app = APPS.find((each) => each.path === `/${id}`)!;
    return { id, name: app.name, icon: app.icon };
  });

  // the version the SAK instance runs, once it is known
  protected readonly version = signal<string | null>(null);
  // why there are no links
  protected readonly failed = signal<TextKey | null>(null);

  constructor() {
    inject(AuthService)
      .info()
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe({
        next: (info) => {
          if (info.version) this.version.set(info.version);
          else this.failed.set('app_download.no_release');
        },
        error: () => this.failed.set('app_download.failed'),
      });
  }

  protected url(app: AndroidApp, version: string): string {
    return apkUrl(app, version);
  }
}
