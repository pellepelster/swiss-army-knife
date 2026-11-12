import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { debounceTime, Subscription } from 'rxjs';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { ActionMenu } from './action-menu';
import { apiErrorMessage } from './api';
import { LIST_APP } from './list-app';
import { CHANGES_DEBOUNCE_MS } from './list-changes';
import { ListIcon } from './list-icon';
import { SharedList } from './list-service';
import { PageHeader } from './page-header';
import { RowError } from './row-error';

// the start of a list-based app (LIST-3, the app is LIST_APP): all lists of the user, the own and
// the shared ones of the others, each with how many of its items are open; a list opens on its own
// page, its menu leads to its settings and deletes it. A change of a list loads them again (LIST-4)
@Component({
  selector: 'app-lists-page',
  imports: [PageHeader, ListIcon, RouterLink, ActionMenu, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ texts.title | t }}</h1>
      <a class="btn btn-primary" routerLink="lists/create" [title]="texts.create | t">
        <i class="sak-icon-plus" aria-hidden="true"></i> {{ texts.create | t }}
      </a>
    </header>

    @if (error() ?? strayError(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (lists().length) {
      <ul class="list-unstyled vstack gap-2 mb-0" [attr.aria-label]="texts.lists | t">
        @for (list of lists(); track list.id) {
          <!-- the row opens the list; its menu the settings and the delete, only of a list of the
               user's own (UX-8) -->
          <li class="card card-body flex-row flex-wrap align-items-center gap-3">
            <app-list-icon [name]="list.name" [icon]="list.icon" />
            <a
              class="flex-grow-1 link-body-emphasis text-decoration-none stretched-link"
              [routerLink]="['lists', list.id]"
              [attr.data-testid]="'list-' + list.id"
            >
              <span class="d-block fs-5 text-break">{{ list.name }}</span>
              <span class="d-block text-body-secondary">
                {{ count(list.items) }}
                @if (list.shared) {
                  <span class="badge border text-body-secondary fw-normal ms-1">{{
                    texts.sharedBadge | t
                  }}</span>
                }
              </span>
            </a>
            <app-action-menu
              [name]="list.name"
              deletes
              [deleteDisabled]="undeletable(list)"
              (deleted)="delete(list)"
            >
              <a
                class="dropdown-item"
                role="menuitem"
                [routerLink]="['lists', list.id, 'edit']"
                [queryParams]="{ from: 'lists' }"
                [attr.aria-label]="texts.settingsOf | t: { name: list.name }"
                ><i class="fa-solid fa-gear me-2" aria-hidden="true"></i
                >{{ 'shared.settings' | t }}</a
              >
            </app-action-menu>
            <!-- the error of the menu of the row, in the row (UX-15), above its link -->
            @if (rowError.of(list.id); as error) {
              <p class="alert alert-danger mb-0 w-100 position-relative z-2" role="alert">
                {{ error }}
              </p>
            }
          </li>
        }
      </ul>
    } @else if (loaded()) {
      <p class="text-center text-body-secondary fs-4 py-5">{{ texts.empty | t }}</p>
    }
  `,
})
export class ListsPage {
  private readonly destroyRef = inject(DestroyRef);
  private readonly app = inject(LIST_APP);
  private readonly service = inject(this.app.service);
  private readonly i18n = inject(I18n);
  private loading?: Subscription;

  protected readonly texts = this.app.texts;
  protected readonly lists = signal<SharedList[]>([]);
  protected readonly loaded = signal(false);
  // the lists could not be loaded
  protected readonly error = signal<string | null>(null);
  // the error of a delete from the menu of a row, by the id of its list
  protected readonly rowError = new RowError<number>();
  protected strayError(): string | null {
    return this.rowError.stray((id) => this.lists().some((list) => list.id === id));
  }

  constructor() {
    this.load();
    inject(this.app.changes)
      .changes()
      .pipe(debounceTime(CHANGES_DEBOUNCE_MS), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load());
  }

  private load(): void {
    this.loading?.unsubscribe();
    this.loading = this.service
      .lists()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (lists) => {
          this.error.set(null);
          this.lists.set(lists);
          this.loaded.set(true);
        },
        error: (error: unknown) =>
          this.error.set(
            apiErrorMessage(error, this.i18n.t(this.texts.listsNotLoaded), {
              403: this.i18n.t(this.texts.listsForbidden),
            }),
          ),
      });
  }

  // why a list can not be deleted: only its owner deletes it (LIST-2)
  protected undeletable(list: SharedList): string | null {
    return list.mine ? null : this.i18n.t(this.texts.onlyOwnerDeletes);
  }

  protected delete(list: SharedList): void {
    this.rowError.clear();
    this.service
      .deleteList(list.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.load(),
        error: (error: unknown) =>
          this.rowError.set(list.id, apiErrorMessage(error, this.i18n.t(this.texts.notDeleted))),
      });
  }

  // e.g. No open tasks, 1 open task, 3 open tasks
  protected count(items: number): string {
    if (items === 0) return this.i18n.t(this.texts.countNone);
    if (items === 1) return this.i18n.t(this.texts.countOne);
    return this.i18n.t(this.texts.countOther, { count: items });
  }
}
