import { NgTemplateOutlet } from '@angular/common';
import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, filter, Observable, Subscription } from 'rxjs';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { PendingUndo } from '../shared/pending-undo';
import { UndoToast } from '../shared/undo-toast';
import { ListIcon } from './list-icon';
import { CHANGES_DEBOUNCE_MS, EVERYTHING, GroceryChanges } from './grocery-changes';
import {
  GroceryCategory,
  GroceryItem,
  GroceryList,
  GroceryService,
  GrocerySuggestion,
} from './grocery.service';
import { PageHeader } from '../shared/page-header';

// how long a check can be undone, shorter than for the notes: while shopping one
// item follows the other
export const GROCERY_UNDO_MS = 1500;

// the ids but the one
function without(ids: ReadonlySet<number>, id: number): ReadonlySet<number> {
  return new Set([...ids].filter((each) => each !== id));
}

// one group of the items to buy, of a category or of none
interface ItemGroup {
  label: string;
  items: GroceryItem[];
}

// one grocery list, opened from all lists (LIST-3), with its items to buy by category; a bought item
// is struck through while it can be undone and goes afterwards (GROCERY-3). A new item goes to the
// list shown, the suggestions are the items added before. A list that is gone leads to all lists
@Component({
  selector: 'app-grocery-page',
  imports: [PageHeader, ListIcon, NgTemplateOutlet, RouterLink, TranslatePipe, UndoToast],
  host: {
    class: 'd-block container pb-5',
  },
  template: `
    <header appPageHeader back="/grocery" [backLabel]="'grocery.all_lists' | t">
      @if (shown(); as list) {
        <app-list-icon [name]="list.name" [icon]="list.icon" />
      }
      <h1 class="h3 mb-0 flex-grow-1 text-break" data-testid="list-name">
        {{ shown()?.name }}
        @if (shown()?.shared) {
          <span class="badge border text-body-secondary fw-normal fs-6 align-middle">{{
            'grocery.shared_badge' | t
          }}</span>
        }
      </h1>
      <!-- always in its place (UX-8): only the owner changes a list -->
      @if (shown()?.mine) {
        <a class="btn btn-primary" [routerLink]="['/grocery/lists', shown()!.id, 'edit']"
          ><i class="fa-solid fa-gear" aria-hidden="true"></i> {{ 'shared.settings' | t }}</a
        >
      } @else {
        <button type="button" class="btn btn-primary" disabled [title]="'grocery.only_owner' | t">
          <i class="fa-solid fa-gear" aria-hidden="true"></i> {{ 'shared.settings' | t }}
        </button>
      }
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (shown()) {
      <form class="card card-body mb-4" (submit)="$event.preventDefault(); add()">
        <label class="form-label" for="item-name">{{ 'grocery.list.item' | t }}</label>
        <div class="input-group">
          <input
            #name
            id="item-name"
            class="form-control"
            list="grocery-suggestions"
            autocomplete="off"
            maxlength="128"
            [value]="itemName()"
            (input)="typed(name.value)"
          />
          <button class="btn btn-primary" type="submit" [disabled]="!itemName().trim() || adding()">
            @if (adding()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            } @else {
              <i class="fa-solid fa-cart-shopping" aria-hidden="true"></i>
            }
            {{ 'grocery.list.add_item' | t }}
          </button>
        </div>
        <datalist id="grocery-suggestions">
          @for (suggestion of suggestions(); track suggestion.id) {
            <option [value]="suggestion.name"></option>
          }
        </datalist>
      </form>

      @for (group of toBuy(); track group.label) {
        <h2 class="h6 text-body-secondary mt-3">{{ group.label }}</h2>
        <ul class="list-group mb-3" [attr.aria-label]="group.label">
          @for (item of group.items; track item.id) {
            <ng-container *ngTemplateOutlet="row; context: { $implicit: item }"></ng-container>
          }
        </ul>
      }

      @if (loaded() && !toBuy().length) {
        <p class="text-center text-body-secondary fs-4 py-5">{{ 'grocery.list.empty' | t }}</p>
      }
    }

    <ng-template #row let-item>
      <li
        class="list-group-item d-flex flex-wrap align-items-center gap-2"
        [attr.data-testid]="'item-' + item.id"
      >
        <input
          class="form-check-input mt-0"
          type="checkbox"
          [id]="'item-' + item.id"
          [checked]="struck().has(item.id)"
          (change)="check(item)"
        />
        <label
          class="form-check-label flex-grow-1 text-break"
          [class.text-decoration-line-through]="struck().has(item.id)"
          [class.text-body-secondary]="struck().has(item.id)"
          [for]="'item-' + item.id"
        >
          {{ item.name }}
          @if (item.amount !== null) {
            <span class="text-body-secondary">· {{ item.amount }} {{ item.unit ?? '' }}</span>
          }
        </label>
      </li>
    </ng-template>

    @for (each of undoable(); track each) {
      <app-undo-toast [text]="each.text" [ms]="undoMs" (undo)="undo()" />
    }
  `,
})
export class GroceryPage {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(GroceryService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  // the route parameter, the id of the list shown
  readonly id = input.required<string>();

  protected readonly lists = signal<GroceryList[]>([]);
  protected readonly categories = signal<GroceryCategory[]>([]);
  protected readonly items = signal<GroceryItem[]>([]);
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly suggestions = signal<GrocerySuggestion[]>([]);
  protected readonly itemName = signal('');
  // the quantity and the category of the suggestion chosen, taken over without being shown
  private readonly itemAmount = signal('');
  private readonly itemUnit = signal('');
  private readonly itemCategory = signal<string | null>(null);
  protected readonly adding = signal(false);

  protected readonly undoMs = GROCERY_UNDO_MS;

  // the items bought while the bar runs, struck through until they go
  protected readonly struck = signal<ReadonlySet<number>>(new Set());

  // what was bought last while it can be undone: what the toast says
  private readonly pending = new PendingUndo<{ text: string }>(GROCERY_UNDO_MS);
  // a list, so the bar showing the time left starts anew for the next one
  protected readonly undoable = computed(() => {
    const current = this.pending.current();
    return current ? [current] : [];
  });

  protected readonly shown = computed(
    () => this.lists().find((list) => String(list.id) === this.id()) ?? null,
  );

  // the items to buy by category, in the order of the categories, the ones without one last
  protected readonly toBuy = computed<ItemGroup[]>(() => {
    const groups = new Map<string, ItemGroup>();
    for (const item of this.items()) {
      const label =
        this.categories().find((each) => each.name === item.category)?.label ??
        this.i18n.t('grocery.list.no_category');
      if (!groups.has(label)) groups.set(label, { label, items: [] });
      groups.get(label)!.items.push(item);
    }
    return [...groups.values()];
  });

  private loadingItems?: Subscription;
  private loadingLists?: Subscription;
  private loadingSuggestions?: Subscription;

  constructor() {
    this.service
      .categories()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (categories) => this.categories.set(categories) });
    this.loadLists();
    // the items of the list shown, anew when another one is chosen; what was done on the list
    // before goes to the backend
    effect(() => {
      const list = this.shown();
      untracked(() => {
        this.pending.commit();
        this.loadItems(list);
      });
    });
    this.destroyRef.onDestroy(() => this.pending.commit());
    // a change of the list shown, or of everything after the stream was away, loads it again; a list
    // that is gone leads to all lists (LIST-4)
    inject(GroceryChanges)
      .changes()
      .pipe(
        filter((id) => id === EVERYTHING || String(id) === this.id()),
        debounceTime(CHANGES_DEBOUNCE_MS),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.loadLists();
        this.loadItems(this.shown());
      });
  }

  // the suggestions of what is typed; one chosen brings its quantity and category along, typing
  // another name drops them
  protected typed(name: string): void {
    this.itemName.set(name);
    const chosen = this.suggestions().find((each) => each.name === name);
    if (chosen) {
      this.itemAmount.set(chosen.amount === null ? '' : String(chosen.amount));
      this.itemUnit.set(chosen.unit ?? '');
      this.itemCategory.set(chosen.category);
      return;
    }
    this.itemAmount.set('');
    this.itemUnit.set('');
    this.itemCategory.set(null);
    this.loadingSuggestions?.unsubscribe();
    this.loadingSuggestions = this.service
      .suggestions(name, this.shown()?.id ?? null)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (suggestions) => this.suggestions.set(suggestions) });
  }

  protected add(): void {
    const name = this.itemName().trim();
    if (!name) return;
    const amount = this.itemAmount().trim() === '' ? null : Number(this.itemAmount());
    const unit = this.itemUnit().trim() || null;
    this.adding.set(true);
    this.error.set(null);
    const list = this.shown();
    if (!list) return;
    this.service
      .addItem(list.id, {
        name,
        amount,
        unit,
        category: this.itemCategory(),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.adding.set(false);
          this.itemName.set('');
          this.itemAmount.set('');
          this.itemUnit.set('');
          this.itemCategory.set(null);
          this.suggestions.set([]);
          this.loadItems(this.shown());
        },
        error: (error: unknown) => {
          this.adding.set(false);
          this.error.set(apiErrorMessage(error, this.i18n.t('grocery.list.not_added')));
        },
      });
  }

  // the item is bought and struck through, after GROCERY_UNDO_MS the backend learns it and it goes
  // from the list, unless it is undone (GROCERY-3)
  protected check(item: GroceryItem): void {
    // checked again while the bar runs: not bought after all
    if (this.struck().has(item.id)) {
      this.undo();
      return;
    }
    this.error.set(null);
    this.pending.start(
      { text: this.i18n.t('grocery.list.bought', { name: item.name }) },
      () => {
        this.struck.update((ids) => without(ids, item.id));
        this.items.update((items) => items.filter((each) => each.id !== item.id));
        this.send(this.service.deleteItem(item.id), this.i18n.t('grocery.list.not_checked'));
      },
      () => this.struck.update((ids) => without(ids, item.id)),
    );
    this.struck.update((ids) => new Set([...ids, item.id]));
  }

  protected undo(): void {
    this.pending.undo();
  }

  // not ended with the page: what was done is sent also when the page is left; a failure brings
  // the list back as the backend has it
  private send(request: Observable<unknown>, failure: string): void {
    request.subscribe({
      error: (error: unknown) => {
        this.error.set(apiErrorMessage(error, failure));
        this.loadItems(this.shown());
      },
    });
  }

  private loadLists(): void {
    this.loadingLists?.unsubscribe();
    this.loadingLists = this.service
      .lists()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (lists) => {
          this.lists.set(lists);
          if (!lists.some((list) => String(list.id) === this.id())) {
            void this.router.navigate(['/grocery'], { replaceUrl: true });
          }
        },
        error: (error: unknown) =>
          this.error.set(
            apiErrorMessage(error, this.i18n.t('grocery.lists_not_loaded'), {
              403: this.i18n.t('grocery.lists_forbidden'),
            }),
          ),
      });
  }

  private loadItems(list: GroceryList | null): void {
    this.loadingItems?.unsubscribe();
    if (!list) {
      this.items.set([]);
      return;
    }
    this.loadingItems = this.service
      .items(list.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => {
          this.items.set(items);
          this.loaded.set(true);
        },
        error: (error: unknown) =>
          this.error.set(apiErrorMessage(error, this.i18n.t('grocery.list.items_not_loaded'))),
      });
  }
}
