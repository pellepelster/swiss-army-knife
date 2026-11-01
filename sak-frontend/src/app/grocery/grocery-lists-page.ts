import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, Subscription } from 'rxjs';
import { RouterLink } from '@angular/router';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { CHANGES_DEBOUNCE_MS, GroceryChanges } from './grocery-changes';
import { GroceryList, GroceryService } from './grocery.service';
import { ListIcon } from './list-icon';

// the start of the grocery app (LIST-3): all lists of the user, the own and the shared ones of the
// others, each with how many items are to buy; a list opens on its own page. A change of a list
// loads them again (LIST-4)
@Component({
  selector: 'app-grocery-lists-page',
  imports: [ListIcon, RouterLink, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        [attr.aria-label]="'grocery.all_apps' | t"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'grocery.title' | t }}</h1>
      <a class="btn btn-primary" routerLink="lists/create">
        <i class="sak-icon-plus" aria-hidden="true"></i> {{ 'grocery.lists.add' | t }}
      </a>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (lists().length) {
      <nav class="list-group" [attr.aria-label]="'grocery.lists.label' | t">
        @for (list of lists(); track list.id) {
          <!-- the row opens the list; its gear the settings, only of a list of the user's own (UX-8) -->
          <div class="list-group-item list-group-item-action d-flex align-items-center gap-3 py-3">
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
            @if (list.mine) {
              <a
                class="btn btn-link link-body-emphasis position-relative z-1"
                [routerLink]="['lists', list.id, 'edit']"
                [queryParams]="{ from: 'lists' }"
                [attr.aria-label]="'grocery.settings_of' | t: { name: list.name }"
                [title]="'grocery.settings' | t"
                ><i class="fa-solid fa-gear" aria-hidden="true"></i
              ></a>
            } @else {
              <button
                type="button"
                class="btn btn-link link-body-emphasis position-relative z-1"
                disabled
                [attr.aria-label]="'grocery.settings_of' | t: { name: list.name }"
                [title]="'grocery.only_owner' | t"
              >
                <i class="fa-solid fa-gear" aria-hidden="true"></i>
              </button>
            }
          </div>
        }
      </nav>
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

  // No items to buy, 1 item to buy, 3 items to buy
  protected toBuy(items: number): string {
    if (items === 0) return this.i18n.t('grocery.lists.to_buy_none');
    if (items === 1) return this.i18n.t('grocery.lists.to_buy_one');
    return this.i18n.t('grocery.lists.to_buy_other', { count: items });
  }
}
