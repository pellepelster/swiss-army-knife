import { Component, computed, inject, input, signal } from '@angular/core';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { ActionMenu } from '../shared/action-menu';
import { apiErrorMessage } from '../shared/api';
import { formatBytes } from '../shared/format';
import { PageHeader } from '../shared/page-header';
import { pagedList, pageOffset } from '../shared/paged-list';
import { PaginationControl } from '../shared/pagination';
import { BackupsService } from './backups.service';

// the repositories of restic the instance keeps for the signed in user, with the bytes each takes on
// it (BACKUP-1); a page at a time. The app SAK Backup creates them and backs up into them, so the
// page has no create action and a row opens nothing: its menu copies the address
@Component({
  selector: 'app-repository-list',
  imports: [PageHeader, PaginationControl, ActionMenu, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'app.backups' | t }}</h1>
    </header>

    <p class="text-body-secondary">{{ 'backups.list.intro' | t }}</p>

    @if (list.error() ?? copyError(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (list.loaded()) {
      <ul class="list-unstyled vstack gap-2 mb-0" [attr.aria-label]="'app.backups' | t">
        @for (repository of list.items(); track repository.id) {
          <li
            class="card card-body flex-row flex-wrap align-items-center gap-3"
            [attr.data-testid]="'repository-' + repository.name"
          >
            <div class="vstack gap-1 flex-grow-1 min-w-0">
              <span class="fw-semibold text-break">{{ repository.name }}</span>
              <span class="small text-body-secondary font-monospace text-break">{{
                repository.url
              }}</span>
              <span class="small text-body-secondary">{{
                'backups.list.created' | t: { date: date(repository.createdAt) }
              }}</span>
            </div>
            <span
              class="fs-5 fw-semibold font-monospace"
              data-testid="size"
              [attr.aria-label]="('backups.list.size' | t) + ' ' + bytes(repository.size)"
              >{{ bytes(repository.size) }}</span
            >
            <app-action-menu
              [name]="repository.name"
              deletes
              [deleteDisabled]="'backups.list.delete_unavailable' | t"
            >
              <button
                type="button"
                class="dropdown-item"
                role="menuitem"
                (click)="copy(repository.url)"
              >
                <i class="fa-solid fa-copy me-2" aria-hidden="true"></i
                >{{ 'backups.list.copy_url' | t }}
              </button>
            </app-action-menu>
          </li>
        } @empty {
          <li class="card card-body text-center py-4">{{ 'backups.list.empty' | t }}</li>
        }
      </ul>
      @if (list.items().length > 1) {
        <p class="text-body-secondary text-end mt-2" data-testid="total">
          {{ 'backups.list.total' | t: { size: bytes(total()) } }}
        </p>
      }
      <app-pagination
        [label]="'backups.list.pages' | t"
        [pagination]="list.pagination()"
        (offsetChange)="list.goTo($event)"
      />
    } @else if (!list.error()) {
      <p class="text-body-secondary">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class RepositoryList {
  private readonly service = inject(BackupsService);
  private readonly i18n = inject(I18n);

  // the offset query parameter
  readonly offset = input(0, { transform: pageOffset });

  protected readonly list = pagedList({
    path: '/backups',
    offset: this.offset,
    page: (offset) => this.service.page(offset),
    error: (error) => apiErrorMessage(error, this.i18n.t('backups.list.load_failed')),
  });

  // the bytes of the repositories of the page
  protected readonly total = computed(() =>
    this.list.items().reduce((sum, each) => sum + each.size, 0),
  );

  protected readonly copyError = signal<string | null>(null);

  protected bytes(value: number): string {
    return formatBytes(value);
  }

  // in the locale of the user (UX-5)
  protected date(value: string): string {
    return new Date(value).toLocaleDateString();
  }

  protected copy(url: string): void {
    this.copyError.set(null);
    navigator.clipboard.writeText(url).catch((error: unknown) => this.copyError.set(String(error)));
  }
}
