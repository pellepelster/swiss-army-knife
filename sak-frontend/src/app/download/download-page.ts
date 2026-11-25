import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { APPS } from '../apps';
import { AuthService, PageHeader, TextKey, TranslatePipe } from '@sak/core';
import { AndroidApp, apkUrl, RELEASES_URL, releaseUrl } from '@sak/core/app-download';

// a file of an app in the release of a version, with what its button says
interface DownloadFile {
  readonly label: TextKey;
  readonly url: (version: string) => string;
}

// an app to download, with its files, e.g. one for each platform
interface DownloadApp {
  readonly id: string;
  readonly name: TextKey;
  readonly icon: string;
  readonly files: readonly DownloadFile[];
}

// the apps of one kind, e.g. the android apps, and what to do with them
interface DownloadGroup {
  readonly id: string;
  readonly title: TextKey;
  readonly intro: TextKey;
  readonly apps: readonly DownloadApp[];
}

// the apps that have an android app, in the order of the index page; one apk each
const ANDROID_APPS: readonly AndroidApp[] = ['notes', 'grocery', 'todo'];

const android: DownloadGroup = {
  id: 'android',
  title: 'download.android',
  intro: 'download.android_intro',
  apps: ANDROID_APPS.map((id) => {
    const app = APPS.find((each) => each.path === `/${id}`)!;
    return {
      id,
      name: app.name,
      icon: app.icon,
      files: [{ label: 'download.download', url: (version: string) => apkUrl(id, version) }],
    };
  }),
};

// a file of sakbackup in a release, e.g. sak-backup-linux-x86_64-1.4.2.deb
const sakBackupFile = (label: TextKey, platform: string, extension: string): DownloadFile => ({
  label,
  url: (version) => releaseUrl(version, `sak-backup-${platform}-${version}.${extension}`),
});

const desktop: DownloadGroup = {
  id: 'desktop',
  title: 'download.desktop',
  intro: 'download.desktop_intro',
  apps: [
    {
      id: 'sak-backup',
      name: 'download.sak_backup',
      icon: APPS.find((each) => each.path === '/backups')!.icon,
      files: [
        sakBackupFile('download.windows', 'windows-x86_64', 'zip'),
        sakBackupFile('download.macos', 'macos-universal', 'zip'),
        sakBackupFile('download.linux_deb', 'linux-x86_64', 'deb'),
        sakBackupFile('download.linux_rpm', 'linux-x86_64', 'rpm'),
        sakBackupFile('download.linux_appimage', 'linux-x86_64', 'AppImage'),
      ],
    },
  ],
};

// /download, without a session so its link opens on a phone: the files of every app with an install
// of its own, the android apps and SAK Backup, in the version the SAK instance runs, from its github
// release; a SAK instance without a release, or one that does not answer, links the releases
@Component({
  selector: 'app-download-page',
  imports: [PageHeader, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1">{{ 'download.title' | t }}</h1>
    </header>

    @if (failed(); as failed) {
      <p class="alert alert-danger" role="alert">{{ failed | t }}</p>
      <a [href]="releases">{{ 'app_download.releases' | t }}</a>
    } @else if (version(); as version) {
      @for (group of groups; track group.id) {
        <section class="mb-4" [attr.data-testid]="'group-' + group.id">
          <h2 class="h5">{{ group.title | t }}</h2>
          <p>{{ group.intro | t }}</p>
          <ul class="row row-cols-1 row-cols-sm-2 g-3 list-unstyled mb-0">
            @for (app of group.apps; track app.id) {
              <li class="col" [attr.data-testid]="'app-' + app.id">
                <div class="card card-body h-100 gap-3">
                  <div class="d-flex align-items-center gap-3">
                    <img
                      class="rounded-3 shadow-sm"
                      [src]="app.icon"
                      alt=""
                      width="48"
                      height="48"
                    />
                    <span class="vstack">
                      <span class="fw-semibold fs-5" data-testid="app-name">{{
                        app.name | t
                      }}</span>
                      <span class="small text-body-secondary">{{
                        'download.version' | t: { version }
                      }}</span>
                    </span>
                  </div>
                  <div
                    class="d-flex flex-wrap gap-2"
                    role="group"
                    [attr.aria-label]="'download.files' | t: { app: (app.name | t) }"
                  >
                    @for (file of app.files; track file.label) {
                      <a
                        class="btn btn-outline-secondary"
                        [href]="file.url(version)"
                        data-testid="file"
                      >
                        <i class="fa-solid fa-download" aria-hidden="true"></i>
                        {{ file.label | t: { app: (app.name | t) } }}
                      </a>
                    }
                  </div>
                </div>
              </li>
            }
          </ul>
        </section>
      }
    } @else {
      <p class="mb-0">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class DownloadPage {
  protected readonly releases = RELEASES_URL;
  protected readonly groups: readonly DownloadGroup[] = [android, desktop];

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
}
