import { DOCUMENT, NgTemplateOutlet } from '@angular/common';
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
import { Observable, Subscription } from 'rxjs';
import { apiErrorMessage } from '../shared/api';
import { UndoToast } from '../shared/undo-toast';
import {
  GroceryCategory,
  GroceryItem,
  GroceryList,
  GroceryService,
  GrocerySuggestion,
} from './grocery.service';

// how long a check can be undone, shorter than for the notes: while shopping one
// item follows the other
export const GROCERY_UNDO_MS = 1500;

// the ids but the one
function without(ids: ReadonlySet<number>, id: number): ReadonlySet<number> {
  return new Set([...ids].filter((each) => each !== id));
}

// the values of an item as the backend takes them, no other fields
function values(item: GroceryItem, checked: boolean) {
  return {
    name: item.name,
    amount: item.amount,
    unit: item.unit,
    category: item.category,
    checked,
  };
}

// a swipe of the lists: how far to the side at least, how long at most, and the edges of the
// screen it does not start at
const SWIPE_DISTANCE = 60;
const SWIPE_TIME_MS = 500;
const SWIPE_EDGE = 20;

// one group of the items to buy, of a category or of none
interface ItemGroup {
  label: string;
  items: GroceryItem[];
}

// a grocery list with its items to buy by category, a bought item is struck through while it can be
// undone and goes afterwards; a new item goes to the list
// shown, to the default list while the user has none, which the first item makes. The suggestions
// are the items added before
@Component({
  selector: 'app-grocery-page',
  imports: [NgTemplateOutlet, RouterLink, UndoToast],
  host: {
    class: 'd-block container pb-5',
    '(touchstart)': 'touchStart($event)',
    '(touchend)': 'touchEnd($event)',
  },
  styles: `
    // a swipe to the side goes to another list, scrolling and zooming stay with the browser
    :host {
      touch-action: pan-y pinch-zoom;
    }

    // big enough to hit with a thumb, at the edges of the page
    .list-arrow {
      z-index: 1020;
      font-size: 3rem;
      padding: 1rem 0.5rem;
    }

    // on a phone the lists are swiped; the arrows stay for the keyboard and the screen readers
    @media (max-width: 767.98px) {
      .list-arrow:not(:focus) {
        position: absolute !important;
        width: 1px;
        height: 1px;
        margin: -1px;
        padding: 0;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
        white-space: nowrap;
        border: 0;
      }
    }

    // where the list shown is among the lists
    .list-dots span {
      display: inline-block;
      width: 0.5rem;
      height: 0.5rem;
      border-radius: 50%;
      background: var(--line);
    }

    .list-dots span.active {
      background: var(--ink);
    }
  `,
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        aria-label="All apps"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">Grocery</h1>
      <div class="btn-group" role="group" aria-label="Lists">
        @if (shown()?.mine) {
          <a class="btn btn-outline-secondary" [routerLink]="['lists', shown()!.id, 'edit']"
            >Edit list</a
          >
        }
        <a class="btn btn-primary" routerLink="lists/create">
          <i class="sak-icon-plus" aria-hidden="true"></i> Add list
        </a>
      </div>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (shown(); as list) {
      <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
        <h2 class="h4 mb-0 flex-grow-1 text-break" data-testid="list-name">
          {{ list.name }}
          @if (list.shared) {
            <span class="badge border text-body-secondary fw-normal fs-6 align-middle">shared</span>
          }
        </h2>
      </div>
      @if (lists().length > 1) {
        <div class="list-dots d-flex gap-1 mb-3" aria-hidden="true" data-testid="list-dots">
          @for (each of lists(); track each.id) {
            <span [class.active]="each.id === list.id"></span>
          }
        </div>
      }
    }

    <!-- the lists one after the other, big arrows at the sides of the page -->
    @if (lists().length > 1) {
      <button
        type="button"
        class="list-arrow btn btn-link link-body-emphasis position-fixed top-50 start-0 translate-middle-y"
        aria-label="Previous list"
        [disabled]="position() === 0"
        (click)="step(-1)"
      >
        <i class="fa-solid fa-chevron-left" aria-hidden="true"></i>
      </button>
      <button
        type="button"
        class="list-arrow btn btn-link link-body-emphasis position-fixed top-50 end-0 translate-middle-y"
        aria-label="Next list"
        [disabled]="position() === lists().length - 1"
        (click)="step(1)"
      >
        <i class="fa-solid fa-chevron-right" aria-hidden="true"></i>
      </button>
    }

    <form class="card card-body mb-4" (submit)="$event.preventDefault(); add()">
      <label class="form-label" for="item-name">Item</label>
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
          Add item
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
      <p class="text-center text-body-secondary fs-4 py-5">Nothing to buy yet, add something.</p>
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
  private readonly document = inject(DOCUMENT);

  // the query parameter, the id of the list shown; the first list without one
  readonly list = input<string | undefined>();

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

  // what was bought last while it can be undone; a list, so the bar showing the time
  // left starts anew for the next one
  protected readonly undoable = signal<{ text: string }[]>([]);

  // what was done last while it can be undone: the timer sending it to the backend, how it is sent
  // and how it is undone
  private pending: {
    timer: ReturnType<typeof setTimeout>;
    commit: () => void;
    undo: () => void;
  } | null = null;

  protected readonly shown = computed(
    () => this.lists().find((list) => String(list.id) === this.list()) ?? this.lists()[0] ?? null,
  );

  // the items to buy by category, in the order of the categories, the ones without one last
  protected readonly toBuy = computed<ItemGroup[]>(() => {
    const groups = new Map<string, ItemGroup>();
    for (const item of this.items().filter((each) => !each.checked)) {
      const label =
        this.categories().find((each) => each.name === item.category)?.label ?? 'No category';
      if (!groups.has(label)) groups.set(label, { label, items: [] });
      groups.get(label)!.items.push(item);
    }
    return [...groups.values()];
  });

  private loadingItems?: Subscription;
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
        this.commitPending();
        this.loadItems(list);
      });
    });
    this.destroyRef.onDestroy(() => this.commitPending());
  }

  // the place of the list shown among the lists
  protected readonly position = computed(() =>
    this.lists().findIndex((list) => list.id === this.shown()?.id),
  );

  // the list before or after the one shown
  protected step(by: number): void {
    const list = this.lists()[this.position() + by];
    if (list) void this.router.navigate([], { queryParams: { list: list.id } });
  }

  // where and when a swipe started, null for a touch that is no swipe of the lists
  private swipe: { x: number; y: number; at: number } | null = null;

  // a swipe starts on the page itself, not on what takes input, and not at the edges of the screen,
  // where the system goes back
  protected touchStart(event: TouchEvent): void {
    const touch = event.touches[0];
    const target = event.target as Element | null;
    const width = this.document.defaultView?.innerWidth ?? 0;
    this.swipe =
      event.touches.length === 1 &&
      !target?.closest('input, select, textarea, button, a, label') &&
      touch.clientX >= SWIPE_EDGE &&
      touch.clientX <= width - SWIPE_EDGE
        ? { x: touch.clientX, y: touch.clientY, at: Date.now() }
        : null;
  }

  // a quick swipe far enough to the side and more to the side than up or down; to the left the next
  // list, to the right the previous one
  protected touchEnd(event: TouchEvent): void {
    const start = this.swipe;
    this.swipe = null;
    const touch = event.changedTouches[0];
    if (!start || !touch) return;
    const dx = touch.clientX - start.x;
    const dy = touch.clientY - start.y;
    if (
      Math.abs(dx) >= SWIPE_DISTANCE &&
      Math.abs(dx) > 2 * Math.abs(dy) &&
      Date.now() - start.at <= SWIPE_TIME_MS
    ) {
      this.step(dx < 0 ? 1 : -1);
    }
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
    const firstItem = this.shown() === null;
    this.service
      .addItem({
        listId: this.shown()?.id ?? null,
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
          // the first item made the default list
          if (firstItem) this.loadLists();
          else this.loadItems(this.shown());
        },
        error: (error: unknown) => {
          this.adding.set(false);
          this.error.set(apiErrorMessage(error, 'The item could not be added.'));
        },
      });
  }

  // the item is bought and struck through, after GROCERY_UNDO_MS the backend learns it and it goes
  // from the list, unless it is undone
  protected check(item: GroceryItem): void {
    // checked again while the bar runs: not bought after all
    if (this.struck().has(item.id)) {
      this.undo();
      return;
    }
    this.startPending(
      `${item.name} bought`,
      () => {
        this.struck.update((ids) => without(ids, item.id));
        this.items.update((items) => items.filter((each) => each.id !== item.id));
        this.send(
          this.service.updateItem(item.id, values(item, true)),
          'The item could not be checked.',
        );
      },
      () => this.struck.update((ids) => without(ids, item.id)),
    );
    this.struck.update((ids) => new Set([...ids, item.id]));
  }

  protected undo(): void {
    const pending = this.pending;
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pending = null;
    this.undoable.set([]);
    pending.undo();
  }

  // the items go from the list shown at once; what was done before goes to the backend now
  private startPending(text: string, commit: () => void, undo: () => void): void {
    this.commitPending();
    this.error.set(null);
    const timer = setTimeout(() => this.commitPending(), GROCERY_UNDO_MS);
    this.pending = { timer, commit, undo };
    this.undoable.set([{ text }]);
  }

  private commitPending(): void {
    const pending = this.pending;
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pending = null;
    this.undoable.set([]);
    pending.commit();
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
    this.service
      .lists()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (lists) => {
          this.lists.set(lists);
          // without a list there are no items to load
          if (!lists.length) this.loaded.set(true);
        },
        error: (error: unknown) =>
          this.error.set(
            apiErrorMessage(error, 'The lists could not be loaded.', {
              403: 'Your role does not allow to see the grocery lists.',
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
          this.error.set(apiErrorMessage(error, 'The items could not be loaded.')),
      });
  }
}
