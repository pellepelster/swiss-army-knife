import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, Subscription } from 'rxjs';
import { RouterLink } from '@angular/router';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { CHANGES_DEBOUNCE_MS, GroceryChanges } from './grocery-changes';
import { GroceryList, GroceryService } from './grocery.service';
import { ListIcon } from './list-icon';
import { ActionMenu } from '../shared/action-menu';
import { PageHeader } from '../shared/page-header';

// the start of the grocery app (LIST-3): all lists of the user, the own and the shared ones of the
// others, each with how many items are to buy; a list opens on its own page, its menu leads to its
// settings and deletes it. A change of a list
// loads them again (LIST-4)
@Component({
  selector: 'app-grocery-lists-page',
  imports: [PageHeader, ListIcon, RouterLink, ActionMenu, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'app.grocery' | t }}</h1>
      <a class="btn btn-primary" routerLink="lists/create" [title]="'grocery.form.create' | t">
        <i class="sak-icon-plus" aria-hidden="true"></i> {{ 'grocery.form.create' | t }}
      </a>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (lists().length) {
      <ul class="list-unstyled vstack gap-2 mb-0" [attr.aria-label]="'grocery.lists.label' | t">
        @for (list of lists(); track list.id) {
          <!-- the row opens the list; its menu the settings and the delete, only of a list of the
               user's own (UX-8), the last own one is kept (LIST-1) -->
          <li class="card card-body flex-row align-items-center gap-3">
            <app-list-icon [name]="list.name" [icon]="list.icon" />
            <a
              class="flex-grow-1 link-body-emphasis text-decoration-none stretched-link"
              [routerLink]="['lists', list.id]"
              [attr.data-testid]="'list-' + list.id"
            >
              <span class="d-block fs-5 text-break">{{ list.name }}</span>
              <span class="d-block text-body-secondary">
                {{ toBuy(list.items) }}
                @if (list.shared) {
                  <span class="badge border text-body-secondary fw-normal ms-1">{{
                    'grocery.shared_badge' | t
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
                [attr.aria-label]="'grocery.settings_of' | t: { name: list.name }"
                ><i class="fa-solid fa-gear me-2" aria-hidden="true"></i
                >{{ 'shared.settings' | t }}</a
              >
            </app-action-menu>
          </li>
        }
      </ul>
    } @else if (loaded()) {
      <p class="text-center text-body-secondary fs-4 py-5">{{ 'grocery.lists.empty' | t }}</p>
    }
  `,
})
export class GroceryListsPage {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(GroceryService);
  private readonly i18n = inject(I18n);
  private loading?: Subscription;

  protected readonly lists = signal<GroceryList[]>([]);
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.load();
    inject(GroceryChanges)
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
            apiErrorMessage(error, this.i18n.t('grocery.lists_not_loaded'), {
              403: this.i18n.t('grocery.lists_forbidden'),
            }),
          ),
      });
  }

  // why a list can not be deleted: of another user, or the last list of the user's own (LIST-1)
  protected undeletable(list: GroceryList): string | null {
    if (!list.mine) return this.i18n.t('grocery.only_owner_deletes');
    if (this.lists().filter((each) => each.mine).length === 1)
      return this.i18n.t('grocery.form.last_list');
    return null;
  }

  protected delete(list: GroceryList): void {
    this.service
      .deleteList(list.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.load(),
        error: (error: unknown) =>
          this.error.set(apiErrorMessage(error, this.i18n.t('grocery.form.not_deleted'))),
      });
  }

  // No items to buy, 1 item to buy, 3 items to buy
  protected toBuy(items: number): string {
    if (items === 0) return this.i18n.t('grocery.lists.to_buy_none');
    if (items === 1) return this.i18n.t('grocery.lists.to_buy_one');
    return this.i18n.t('grocery.lists.to_buy_other', { count: items });
  }
}
