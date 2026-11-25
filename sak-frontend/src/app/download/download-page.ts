import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { APPS } from '../apps';
import { AuthService, PageHeader, TextKey, TranslatePipe } from '@sak/core';
import { AndroidApp, apkUrl, RELEASES_URL, releaseUrl } from '@sak/core/app-download';

// a file of an app in the release of a version, with what its button says
interface DownloadFile {
  readonly label: TextKey;
  readonly url: (version: string) => string;
}

// the files of one platform in several formats, behind one button with a menu, e.g. Linux
interface DownloadFormats {
  readonly label: TextKey;
  readonly formats: readonly DownloadFile[];
}

// an app to download, with its files, e.g. one for each platform
interface DownloadApp {
  readonly id: string;
  readonly name: TextKey;
  // what it is for, as the home page says it
  readonly description: TextKey;
  readonly icon: string;
  readonly files: readonly (DownloadFile | DownloadFormats)[];
}

// the apps that have an android app, in the order of the index page; one apk each
const ANDROID_APPS: readonly AndroidApp[] = ['notes', 'grocery', 'todo'];

// a file of the desktop app Restic Backup (sakbackup) in a release, e.g.
// sak-backup-linux-x86_64-1.4.2.deb
const resticBackupFile = (label: TextKey, platform: string, extension: string): DownloadFile => ({
  label,
  url: (version) => releaseUrl(version, `sak-backup-${platform}-${version}.${extension}`),
});

// every app with an install of its own, by the names inside SAK (the glossary): the android apps,
// then the desktop app
const APPS_TO_DOWNLOAD: readonly DownloadApp[] = [
  ...ANDROID_APPS.map((id) => {
    const app = APPS.find((each) => each.path === `/${id}`)!;
    return {
      id,
      name: app.name,
      description: app.description,
      icon: app.icon,
      files: [
        { label: 'download.android_apk' as TextKey, url: (version: string) => apkUrl(id, version) },
      ],
    };
  }),
  {
    id: 'restic-backup',
    name: 'download.restic_backup',
    description: 'download.restic_backup_description',
    icon: APPS.find((each) => each.path === '/backups')!.icon,
    files: [
      resticBackupFile('download.windows', 'windows-x86_64', 'zip'),
      resticBackupFile('download.macos', 'macos-universal', 'zip'),
      {
        label: 'download.linux',
        formats: [
          resticBackupFile('download.linux_deb', 'linux-x86_64', 'deb'),
          resticBackupFile('download.linux_rpm', 'linux-x86_64', 'rpm'),
          resticBackupFile('download.linux_appimage', 'linux-x86_64', 'AppImage'),
          // the program alone, without a package (desktop:build zips it)
          resticBackupFile('download.linux_binary', 'linux-x86_64', 'zip'),
        ],
      },
    ],
  },
];

// /download, without a session so its link opens on a phone: the files of every app with an install
// of its own, the android apps and Restic Backup, one button for each platform, in the version the SAK
// instance runs, from its github release; a SAK instance without a release, or one that does not
// answer, links the releases
@Component({
  selector: 'app-download-page',
  imports: [PageHeader, TranslatePipe],
  host: {
    class: 'd-block container pb-5',
    '(document:click)': 'clickedOutside($event)',
    '(document:keydown.escape)': 'openMenu.set(null)',
  },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1">{{ 'download.title' | t }}</h1>
    </header>

    @if (failed(); as failed) {
      <p class="alert alert-danger" role="alert">{{ failed | t }}</p>
      <a [href]="releases">{{ 'app_download.releases' | t }}</a>
    } @else if (version(); as version) {
      <ul class="row row-cols-1 row-cols-sm-2 g-3 list-unstyled mb-0">
        @for (app of apps; track app.id) {
          <li class="col" [attr.data-testid]="'app-' + app.id">
            <div class="card card-body h-100 gap-3">
              <div class="d-flex align-items-center gap-3">
                <img class="rounded-3 shadow-sm" [src]="app.icon" alt="" width="48" height="48" />
                <span class="vstack">
                  <span class="fw-semibold fs-5" data-testid="app-name">{{ app.name | t }}</span>
                  <span class="small text-body-secondary" data-testid="app-description">{{
                    app.description | t
                  }}</span>
                </span>
              </div>
              <div
                class="d-flex flex-wrap gap-2"
                role="group"
                [attr.aria-label]="'download.files' | t: { app: (app.name | t) }"
              >
                @for (file of app.files; track file.label) {
                  @if (isFormats(file)) {
                    <!-- one platform in several formats: a button with their menu -->
                    <div class="dropdown" data-testid="formats">
                      <button
                        type="button"
                        class="btn btn-outline-secondary dropdown-toggle"
                        aria-haspopup="menu"
                        [attr.aria-expanded]="openMenu() === app.id + file.label"
                        (click)="toggle(app.id + file.label)"
                      >
                        <i class="fa-solid fa-download" aria-hidden="true"></i>
                        {{ file.label | t }}
                      </button>
                      <div
                        class="dropdown-menu"
                        [class.show]="openMenu() === app.id + file.label"
                        role="menu"
                        [attr.aria-label]="'download.linux_formats' | t: { app: (app.name | t) }"
                      >
                        @for (format of file.formats; track format.label) {
                          <a
                            class="dropdown-item"
                            role="menuitem"
                            [href]="format.url(version)"
                            data-testid="file"
                            (click)="openMenu.set(null)"
                            >{{ format.label | t }}</a
                          >
                        }
                      </div>
                    </div>
                  } @else {
                    <a
                      class="btn btn-outline-secondary"
                      [href]="file.url(version)"
                      data-testid="file"
                    >
                      <i class="fa-solid fa-download" aria-hidden="true"></i>
                      {{ file.label | t: { app: (app.name | t) } }}
                    </a>
                  }
                }
              </div>
            </div>
          </li>
        }
      </ul>
    } @else {
      <p class="mb-0">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class DownloadPage {
  protected readonly releases = RELEASES_URL;
  protected readonly apps = APPS_TO_DOWNLOAD;

  // the version the SAK instance runs, once it is known
  protected readonly version = signal<string | null>(null);
  // why there are no links
  protected readonly failed = signal<TextKey | null>(null);
  // the menu of formats that is open, by its app and label
  protected readonly openMenu = signal<string | null>(null);
  private readonly host = inject(ElementRef<HTMLElement>);

  protected isFormats(file: DownloadFile | DownloadFormats): file is DownloadFormats {
    return 'formats' in file;
  }

  protected toggle(menu: string): void {
    this.openMenu.update((open) => (open === menu ? null : menu));
  }

  // a click outside of a menu closes it
  protected clickedOutside(event: MouseEvent): void {
    const target = event.target as Node | null;
    const inMenu = [...this.host.nativeElement.querySelectorAll('.dropdown')].some((each) =>
      each.contains(target),
    );
    if (!inMenu) this.openMenu.set(null);
  }

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
