import { Component, computed, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  ActionMenu,
  apiErrorMessage,
  formatBytes,
  I18n,
  pagedList,
  PageHeader,
  pageOffset,
  PaginationControl,
  RowError,
  TranslatePipe,
} from '@sak/core';
import { BackupsService } from './backups.service';

// the repositories of restic the instance keeps for the signed in user, with the bytes each takes on
// it (BACKUP-1); a page at a time. The app Restic Backup creates them and backs up into them, so the
// page has no create action and a row opens nothing: its menu copies the address and deletes it, a
// soft delete with its backup plans (BACKUP-9)
@Component({
  selector: 'app-repository-list',
  imports: [PageHeader, PaginationControl, ActionMenu, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'app.backups' | t }}</h1>
    </header>

    <p class="text-body-secondary">{{ 'backups.list.intro' | t }}</p>

    @if (list.error() ?? copyError() ?? strayError(); as error) {
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
              <span class="fw-semibold text-break" data-testid="description">{{
                repository.description
              }}</span>
              <span class="small text-body-secondary font-monospace text-break">{{
                repository.url
              }}</span>
            </div>
            <span
              class="fs-5 fw-semibold font-monospace"
              data-testid="size"
              [attr.aria-label]="('backups.list.size' | t) + ' ' + bytes(repository.size)"
              >{{ bytes(repository.size) }}</span
            >
            <app-action-menu
              [name]="repository.description"
              deletes
              (deleted)="delete(repository.id, repository.description)"
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
            @if (rowError.of(repository.id); as error) {
              <p class="alert alert-danger w-100 mb-0" role="alert">{{ error }}</p>
            }
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
  private readonly destroyRef = inject(DestroyRef);

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

  // by the id of the repository
  protected readonly rowError = new RowError<number>();
  protected strayError(): string | null {
    return this.rowError.stray((id) =>
      this.list.items().some((repository) => repository.id === id),
    );
  }

  protected delete(id: number, name: string): void {
    this.rowError.clear();
    this.service
      .delete(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.list.reload(),
        error: (error: unknown) =>
          this.rowError.set(
            id,
            apiErrorMessage(error, this.i18n.t('backups.list.delete_failed', { name })),
          ),
      });
  }

  protected copy(url: string): void {
    this.copyError.set(null);
    navigator.clipboard.writeText(url).catch((error: unknown) => this.copyError.set(String(error)));
  }
}
