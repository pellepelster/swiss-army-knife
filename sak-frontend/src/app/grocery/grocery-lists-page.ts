import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, Subscription } from 'rxjs';
import { RouterLink } from '@angular/router';
import { apiErrorMessage } from '../shared/api';
import { CHANGES_DEBOUNCE_MS, GroceryChanges } from './grocery-changes';
import { GroceryList, GroceryService } from './grocery.service';
import { ListIcon } from './list-icon';

// the start of the grocery app (LIST-3): all lists of the user, the own and the shared ones of the
// others, each with how many items are to buy; a list opens on its own page. A change of a list
// loads them again (LIST-4)
@Component({
  selector: 'app-grocery-lists-page',
  imports: [ListIcon, RouterLink],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        aria-label="All apps"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">Grocery</h1>
      <a class="btn btn-primary" routerLink="lists/create">
        <i class="sak-icon-plus" aria-hidden="true"></i> Add list
      </a>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (lists().length) {
      <nav class="list-group" aria-label="Grocery lists">
        @for (list of lists(); track list.id) {
          <!-- the row opens the list; its menu the settings, only of a list of the user's own (UX-8) -->
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
                  <span class="badge border text-body-secondary fw-normal ms-1">shared</span>
                }
              </span>
            </a>
            @if (list.mine) {
              <a
                class="btn btn-link link-body-emphasis position-relative z-1"
                [routerLink]="['lists', list.id, 'edit']"
                [queryParams]="{ from: 'lists' }"
                [attr.aria-label]="'Settings of ' + list.name"
                title="Settings"
                ><i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i
              ></a>
            } @else {
              <button
                type="button"
                class="btn btn-link link-body-emphasis position-relative z-1"
                disabled
                [attr.aria-label]="'Settings of ' + list.name"
                title="Only the owner of a list changes it"
              >
                <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
              </button>
            }
          </div>
        }
      </nav>
    } @else if (loaded()) {
      <p class="text-center text-body-secondary fs-4 py-5">You have no list yet, add one first.</p>
    }
  `,
})
export class GroceryListsPage {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(GroceryService);
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
            apiErrorMessage(error, 'The lists could not be loaded.', {
              403: 'Your role does not allow to see the grocery lists.',
            }),
          ),
      });
  }

  // No items to buy, 1 item to buy, 3 items to buy
  protected toBuy(items: number): string {
    if (items === 0) return 'No items to buy';
    return items === 1 ? '1 item to buy' : `${items} items to buy`;
  }
}
