import { AsyncPipe, NgTemplateOutlet } from '@angular/common';
import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import {
  catchError,
  debounceTime,
  filter,
  firstValueFrom,
  forkJoin,
  Observable,
  of,
  Subscription,
  throwError,
} from 'rxjs';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { PendingUndo } from '../shared/pending-undo';
import { UndoToast } from '../shared/undo-toast';
import { ListIcon } from './list-icon';
import { GroceryPhotos, PHOTO_IN_ROW, PhotoInRowPipe } from './grocery-photos';
import { PhotoScaler } from '../notes/photo-scaler';
import { ActionMenu } from '../shared/action-menu';
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

// the items to buy first in the order they were added, then the bought ones, the last bought first
function byToBuyThenBought(a: GroceryItem, b: GroceryItem): number {
  if (!a.checkedAt !== !b.checkedAt) return a.checkedAt ? 1 : -1;
  if (a.checkedAt && b.checkedAt && a.checkedAt !== b.checkedAt) {
    return a.checkedAt < b.checkedAt ? 1 : -1;
  }
  return a.id - b.id;
}

// one group of the items to buy, of a category or of none
interface ItemGroup {
  // the id of the category, null for the items without one
  key: number | null;
  label: string;
  // one emoji, null for none
  icon: string | null;
  items: GroceryItem[];
}

// one grocery list, opened from all lists (LIST-3), with its items to buy by category; a bought item
// is struck through while it can be undone and goes afterwards (GROCERY-3). A new item goes to the
// list shown, the suggestions are the items added to it before. A list that is gone leads to all lists
@Component({
  selector: 'app-grocery-page',
  imports: [
    ActionMenu,
    AsyncPipe,
    PageHeader,
    ListIcon,
    NgTemplateOutlet,
    PhotoInRowPipe,
    RouterLink,
    TranslatePipe,
    UndoToast,
  ],
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
      <!-- one toolbar (UX-10): the bought items shown or not (GROCERY-14), the settings, always in
           their place (UX-8): only the owner changes a list -->
      <div class="btn-group" role="group" [attr.aria-label]="'shared.actions' | t">
        <button
          type="button"
          class="btn btn-outline-secondary"
          data-testid="toggle-bought"
          [attr.aria-pressed]="showBought()"
          [attr.aria-label]="'grocery.list.show_bought' | t"
          [title]="(showBought() ? 'grocery.list.hide_bought' : 'grocery.list.show_bought') | t"
          (click)="toggleBought()"
        >
          <i
            class="fa-solid"
            [class.fa-eye]="showBought()"
            [class.fa-eye-slash]="!showBought()"
            aria-hidden="true"
          ></i>
        </button>
        @if (shown()?.mine) {
          <a
            class="btn btn-outline-secondary"
            [routerLink]="['/grocery/lists', shown()!.id, 'edit']"
            ><i class="fa-solid fa-gear" aria-hidden="true"></i> {{ 'shared.settings' | t }}</a
          >
        } @else {
          <button
            type="button"
            class="btn btn-outline-secondary"
            disabled
            [title]="'grocery.only_owner' | t"
          >
            <i class="fa-solid fa-gear" aria-hidden="true"></i> {{ 'shared.settings' | t }}
          </button>
        }
      </div>
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

      @for (group of toBuy(); track group.key) {
        <h2
          class="h6 text-body-secondary mt-3"
          [attr.data-testid]="'group-' + (group.key ?? 'none')"
        >
          @if (group.icon) {
            <span class="me-1" aria-hidden="true">{{ group.icon }}</span>
          }
          {{ group.label }}
        </h2>
        <ul class="list-group mb-3" [attr.aria-label]="group.label">
          @for (item of group.items; track item.id) {
            <ng-container *ngTemplateOutlet="row; context: { $implicit: item }"></ng-container>
          }
        </ul>
      }

      @if (loaded() && !hasToBuy()) {
        <p class="text-center text-body-secondary fs-4 py-5">{{ 'grocery.list.empty' | t }}</p>
      }
    }

    <ng-template #row let-item>
      <!-- the photo shows the photo in full, a tap on the rest of the row checks the item
           (GROCERY-3), struck through while it can be undone; the menu renames the item in its row,
           attaches a photo and sets the category, one of the list or none (GROCERY-13). A bought
           item, shown with the bought ones (GROCERY-14), is struck through, a tap does not check it
           again; its menu changes it as the one of an item to buy -->
      <li
        class="list-group-item p-0 d-flex align-items-center"
        [attr.data-testid]="'item-' + item.id"
      >
        @if (item.photoId) {
          <button
            type="button"
            class="btn p-0 m-2 me-0 flex-shrink-0 border-0"
            [attr.aria-label]="'grocery.photo.of' | t: { name: item.name }"
            (click)="viewPhoto(item)"
          >
            <img
              class="item-photo rounded object-fit-cover"
              [src]="item | photoInRow | async"
              alt=""
              [width]="photoInRow"
              [height]="photoInRow"
            />
          </button>
        }
        @if (renaming() === item.id) {
          <!-- the name in a field: Enter or leaving it saves, Escape keeps the name (UX-6) -->
          <div class="flex-grow-1 min-w-0 px-3 py-2">
            <input
              #renameField
              [id]="'rename-' + item.id"
              class="form-control"
              maxlength="128"
              autocomplete="off"
              [class.is-invalid]="renameEmpty()"
              [attr.aria-label]="'grocery.list.name_of' | t: { name: item.name }"
              [attr.aria-describedby]="renameEmpty() ? 'rename-error-' + item.id : null"
              [value]="item.name"
              (input)="renameEmpty.set(false)"
              (keydown.enter)="$event.preventDefault(); rename(item, renameField, true)"
              (keydown.escape)="$event.preventDefault(); cancelRename(item)"
              (blur)="rename(item, renameField)"
            />
            @if (renameEmpty()) {
              <div class="invalid-feedback d-block" [id]="'rename-error-' + item.id">
                {{ 'grocery.list.name_required' | t }}
              </div>
            }
          </div>
        } @else {
          <button
            type="button"
            class="btn grocery-item flex-grow-1 min-w-0 rounded-0 border-0 text-start d-flex flex-wrap align-items-center gap-2 px-3 py-2"
            [id]="'item-' + item.id"
            [attr.aria-pressed]="struck().has(item.id) || !!item.checkedAt"
            [attr.aria-disabled]="!!item.checkedAt"
            [attr.data-bought]="item.checkedAt ? '' : null"
            (click)="item.checkedAt && !struck().has(item.id) ? null : check(item)"
          >
            <span
              class="item-name flex-grow-1 text-break"
              [class.item-struck]="struck().has(item.id) || !!item.checkedAt"
              [class.text-body-secondary]="struck().has(item.id) || !!item.checkedAt"
            >
              {{ item.name }}
              @if (item.amount !== null) {
                <span class="text-body-secondary">· {{ item.amount }} {{ item.unit ?? '' }}</span>
              }
            </span>
          </button>
        }
        <app-action-menu class="pe-1" [name]="item.name" deletes (deleted)="deleteItem(item)">
          <button
            type="button"
            class="dropdown-item"
            role="menuitem"
            [disabled]="renaming() === item.id"
            [title]="renaming() === item.id ? ('grocery.list.renaming' | t) : ''"
            (click)="startRename(item)"
          >
            <i class="fa-solid fa-pen me-2" aria-hidden="true"></i>{{ 'grocery.list.rename' | t }}
          </button>
          <button
            type="button"
            class="dropdown-item"
            role="menuitem"
            [disabled]="photoBusy() === item.id"
            (click)="choosePhoto(item)"
          >
            <i class="fa-solid fa-camera me-2" aria-hidden="true"></i
            >{{ (item.photoId ? 'grocery.photo.replace' : 'grocery.photo.attach') | t }}
          </button>
          <button
            type="button"
            class="dropdown-item"
            role="menuitem"
            [disabled]="!item.photoId || photoBusy() === item.id"
            (click)="removePhoto(item)"
          >
            <i class="fa-solid fa-image me-2" aria-hidden="true"></i
            >{{ 'grocery.photo.remove' | t }}
          </button>
          <div class="dropdown-divider"></div>
          <h6 class="dropdown-header" [id]="'category-of-' + item.id">
            {{ 'grocery.list.category' | t }}
          </h6>
          <div role="group" [attr.aria-labelledby]="'category-of-' + item.id">
            @for (category of categories(); track category.id) {
              <button
                type="button"
                class="dropdown-item"
                role="menuitemradio"
                [attr.aria-checked]="item.categoryId === category.id"
                [disabled]="categoryBusy() === item.id"
                (click)="setCategory(item, category.id)"
              >
                <span class="d-inline-block me-2 category-icon" aria-hidden="true">{{
                  category.icon ?? ''
                }}</span
                >{{ category.name }}
                @if (item.categoryId === category.id) {
                  <i class="fa-solid fa-check ms-2" aria-hidden="true"></i>
                }
              </button>
            }
            <button
              type="button"
              class="dropdown-item"
              role="menuitemradio"
              [attr.aria-checked]="item.categoryId === null"
              [disabled]="categoryBusy() === item.id"
              (click)="setCategory(item, null)"
            >
              <span class="d-inline-block me-2 category-icon" aria-hidden="true"></span
              >{{ 'grocery.list.no_category' | t }}
              @if (item.categoryId === null) {
                <i class="fa-solid fa-check ms-2" aria-hidden="true"></i>
              }
            </button>
          </div>
        </app-action-menu>
      </li>
    </ng-template>

    <!-- the photo chosen for the item of photoFor; no capture, so a phone offers the camera and the
         photos it has -->
    <input
      #photoInput
      type="file"
      accept="image/*"
      hidden
      data-testid="photo-input"
      (change)="photoChosen($any($event.target))"
    />

    <!-- the photo in full, over the list; a tap or Escape closes it -->
    @if (viewing(); as photo) {
      <div
        class="photo-viewer position-fixed top-0 start-0 w-100 h-100 d-flex align-items-center justify-content-center p-3"
        role="dialog"
        [attr.aria-label]="'grocery.photo.of' | t: { name: photo.name }"
        data-testid="photo-viewer"
        (click)="closePhoto()"
        (keydown.escape)="closePhoto()"
        tabindex="-1"
      >
        @if (photo.url; as url) {
          <img
            class="mw-100 mh-100 rounded"
            [src]="url"
            [alt]="'grocery.photo.of' | t: { name: photo.name }"
          />
        } @else {
          <i class="fa-solid fa-spinner fa-spin fs-1 text-white" aria-hidden="true"></i>
        }
        <button
          type="button"
          class="btn-close btn-close-white position-absolute top-0 end-0 m-3"
          [attr.aria-label]="'grocery.photo.close' | t"
        ></button>
      </div>
    }

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
  private readonly scaler = inject(PhotoScaler);
  private readonly photos = inject(GroceryPhotos);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly injector = inject(Injector);

  // the route parameter, the id of the list shown
  readonly id = input.required<string>();

  protected readonly lists = signal<GroceryList[]>([]);
  protected readonly categories = signal<GroceryCategory[]>([]);
  protected readonly items = signal<GroceryItem[]>([]);
  // the bought items shown too, after the ones to buy of their category (GROCERY-14)
  protected readonly showBought = signal(false);
  // whether an item is to buy, what the empty list says
  protected readonly hasToBuy = computed(() => this.items().some((item) => !item.checkedAt));
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly suggestions = signal<GrocerySuggestion[]>([]);
  protected readonly itemName = signal('');
  // the quantity and the category of the suggestion chosen, taken over without being shown
  private readonly itemAmount = signal('');
  private readonly itemUnit = signal('');
  private readonly itemCategory = signal<number | null>(null);
  // the item whose category is being set
  protected readonly categoryBusy = signal<number | null>(null);
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

  // the items to buy by the categories of the list, in their order, the ones without one last
  // (GROCERY-13)
  protected readonly toBuy = computed<ItemGroup[]>(() => {
    const categories = this.categories();
    const groups: ItemGroup[] = categories.map((category) => ({
      key: category.id,
      label: category.name,
      icon: category.icon,
      items: [],
    }));
    const none: ItemGroup = {
      key: null,
      label: this.i18n.t('grocery.list.no_category'),
      icon: null,
      items: [],
    };
    for (const item of this.items()) {
      const index = categories.findIndex((each) => each.id === item.categoryId);
      (index < 0 ? none : groups[index]).items.push(item);
    }
    // in a group the items to buy first in the order they were added, then the bought ones, the
    // last bought first, as the backend answers them; a category set keeps that order (GROCERY-14)
    for (const group of [...groups, none]) group.items.sort(byToBuyThenBought);
    return [...groups, none].filter((group) => group.items.length);
  });

  // the page is gone: what is sent then loads nothing again
  private destroyed = false;
  private loadingItems?: Subscription;
  private loadingLists?: Subscription;
  private loadingSuggestions?: Subscription;

  constructor() {
    this.loadLists();
    // the items and the categories of the list shown, anew when another one is chosen; what was done on the list
    // before goes to the backend
    effect(() => {
      const list = this.shown();
      untracked(() => {
        this.pending.commit();
        this.loadItems(list);
      });
    });
    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
      this.pending.commit();
    });
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
      this.itemCategory.set(chosen.categoryId);
      return;
    }
    this.itemAmount.set('');
    this.itemUnit.set('');
    this.itemCategory.set(null);
    this.loadingSuggestions?.unsubscribe();
    const shown = this.shown();
    if (!shown) {
      this.suggestions.set([]);
      return;
    }
    this.loadingSuggestions = this.service
      .suggestions(shown.id, name)
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
        categoryId: this.itemCategory(),
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
        this.closeRename(item.id);
        // bought on another device meanwhile: done already, nothing to send
        const bought = !!this.items().find((each) => each.id === item.id)?.checkedAt;
        this.items.update((items) => items.filter((each) => each.id !== item.id));
        // shown with the bought ones, it comes back in its place among them; one bought on another
        // device meanwhile (404) is done too
        const done = () => {
          if (this.showBought()) this.loadItems(this.shown());
        };
        if (bought) {
          done();
          return;
        }
        this.send(
          this.service
            .checkItem(item.id)
            .pipe(
              catchError((error: unknown) =>
                error instanceof HttpErrorResponse && error.status === 404
                  ? of(null)
                  : throwError(() => error),
              ),
            ),
          this.i18n.t('grocery.list.not_checked'),
          done,
        );
      },
      () => this.struck.update((ids) => without(ids, item.id)),
    );
    this.struck.update((ids) => new Set([...ids, item.id]));
  }

  // the bought items shown or not (GROCERY-14)
  protected toggleBought(): void {
    this.showBought.update((shown) => !shown);
    this.loadItems(this.shown());
  }

  // removed for good, not bought: it goes from the list and from the suggestions
  protected deleteItem(item: GroceryItem): void {
    // its check waits for the end of the bar: it goes with its toast, nothing is checked
    if (this.struck().has(item.id)) {
      this.pending.dispose();
      this.struck.update((ids) => without(ids, item.id));
    }
    this.closeRename(item.id);
    this.error.set(null);
    this.items.update((items) => items.filter((each) => each.id !== item.id));
    this.send(this.service.deleteItem(item.id), this.i18n.t('grocery.list.not_deleted'));
  }

  protected undo(): void {
    this.pending.undo();
  }

  // the item whose name is in a field, to fix it, to buy or bought; an empty name is not saved
  protected readonly renaming = signal<number | null>(null);
  protected readonly renameEmpty = signal(false);

  protected startRename(item: GroceryItem): void {
    this.renameEmpty.set(false);
    this.renaming.set(item.id);
    afterNextRender(
      () => {
        const field = this.host.nativeElement.querySelector<HTMLInputElement>(`#rename-${item.id}`);
        field?.focus();
        field?.select();
      },
      { injector: this.injector },
    );
  }

  // Escape keeps the name, the focus goes back to the row
  protected cancelRename(item: GroceryItem): void {
    this.closeRename(item.id);
    this.focusRow(item.id);
  }

  // the field of the item closed, e.g. when the item leaves the list
  private closeRename(id: number): void {
    if (this.renaming() !== id) return;
    this.renameEmpty.set(false);
    this.renaming.set(null);
  }

  // the focus on the row of the item once it shows again, not lost to the page
  private focusRow(id: number): void {
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>(`#item-${id}`)?.focus(),
      { injector: this.injector },
    );
  }

  // the name once Enter is pressed or the field is left, with the quantity and the category the
  // item has; the field goes first, so leaving it after Enter or Escape saves nothing. Enter brings
  // the focus back to the row, leaving the field keeps it where it went
  protected rename(item: GroceryItem, field: HTMLInputElement, enter = false): void {
    if (this.renaming() !== item.id) return;
    const name = field.value.trim();
    if (!name) {
      this.renameEmpty.set(true);
      return;
    }
    this.renaming.set(null);
    if (enter) this.focusRow(item.id);
    if (name === item.name) return;
    this.error.set(null);
    this.service
      .updateItem(item.id, {
        name,
        amount: item.amount,
        unit: item.unit,
        categoryId: item.categoryId,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (changed) =>
          this.items.update((items) =>
            items.map((each) => (each.id === changed.id ? changed : each)),
          ),
        error: (error: unknown) => {
          this.error.set(apiErrorMessage(error, this.i18n.t('grocery.list.not_renamed')));
          this.loadItems(this.shown());
        },
      });
  }

  // the category of the item, one of the list or none; the backend learns it for the next item of
  // the name on the list (GROCERY-13)
  protected setCategory(item: GroceryItem, categoryId: number | null): void {
    if (item.categoryId === categoryId) return;
    this.categoryBusy.set(item.id);
    this.error.set(null);
    this.service
      .setCategory(item.id, categoryId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (changed) => {
          this.categoryBusy.set(null);
          this.items.update((items) =>
            items.map((each) => (each.id === changed.id ? changed : each)),
          );
        },
        error: (error: unknown) => {
          this.categoryBusy.set(null);
          this.error.set(apiErrorMessage(error, this.i18n.t('grocery.list.category_not_set')));
          this.loadItems(this.shown());
        },
      });
  }

  // the item the photo input chooses a photo for, and the item whose photo is being sent
  private photoFor: GroceryItem | null = null;
  protected readonly photoBusy = signal<number | null>(null);
  protected readonly photoInRow = PHOTO_IN_ROW;
  private readonly photoInput = viewChild.required<ElementRef<HTMLInputElement>>('photoInput');

  protected choosePhoto(item: GroceryItem): void {
    this.photoFor = item;
    this.photoInput().nativeElement.click();
  }

  // the photo scaled down and stored, then attached to the item; it shows once the item is loaded
  // again, also on the other screens that show the list (LIST-4)
  protected async photoChosen(input: HTMLInputElement): Promise<void> {
    const file = input.files?.[0];
    const item = this.photoFor;
    input.value = '';
    if (!file || !item) return;
    this.photoBusy.set(item.id);
    this.error.set(null);
    try {
      const scaled = await this.scaler.scale(file);
      const stored = await firstValueFrom(this.service.uploadPhoto(scaled));
      const changed = await firstValueFrom(this.service.attachPhoto(item.id, stored.id));
      this.items.update((items) => items.map((each) => (each.id === changed.id ? changed : each)));
    } catch (error: unknown) {
      this.error.set(apiErrorMessage(error, this.i18n.t('grocery.photo.not_attached')));
    } finally {
      this.photoBusy.set(null);
    }
  }

  protected removePhoto(item: GroceryItem): void {
    this.photoBusy.set(item.id);
    this.error.set(null);
    this.service
      .removePhoto(item.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (changed) => {
          this.photoBusy.set(null);
          this.items.update((items) =>
            items.map((each) => (each.id === changed.id ? changed : each)),
          );
        },
        error: (error: unknown) => {
          this.photoBusy.set(null);
          this.error.set(apiErrorMessage(error, this.i18n.t('grocery.photo.not_removed')));
        },
      });
  }

  // the photo in full: its name at once, the photo once it is fetched in the size of the screen
  protected readonly viewing = signal<{ id: number; name: string; url: string | null } | null>(
    null,
  );

  protected async viewPhoto(item: GroceryItem): Promise<void> {
    this.viewing.set({ id: item.id, name: item.name, url: null });
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('.photo-viewer')?.focus(),
      {
        injector: this.injector,
      },
    );
    try {
      const url = await this.photos.full(item);
      // still the photo shown, not closed or another one meanwhile
      if (this.viewing()?.id === item.id && this.viewing()?.url === null) {
        this.viewing.set({ id: item.id, name: item.name, url });
      } else {
        URL.revokeObjectURL(url);
      }
    } catch (error: unknown) {
      if (this.viewing()?.id !== item.id) return;
      this.viewing.set(null);
      this.error.set(apiErrorMessage(error, this.i18n.t('grocery.photo.not_loaded')));
    }
  }

  protected closePhoto(): void {
    const url = this.viewing()?.url;
    if (url) URL.revokeObjectURL(url);
    this.viewing.set(null);
  }

  // not ended with the page: what was done is sent also when the page is left; a failure brings
  // the list back as the backend has it
  private send(request: Observable<unknown>, failure: string, done?: () => void): void {
    request.subscribe({
      next: () => {
        if (!this.destroyed) done?.();
      },
      error: (error: unknown) => {
        if (this.destroyed) return;
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

  // the items with the categories of the list, together, so the groups fit the items; categories
  // that could not be loaded show the items without them, with the error
  private loadItems(list: GroceryList | null): void {
    if (this.destroyed) return;
    this.loadingItems?.unsubscribe();
    if (!list) {
      this.items.set([]);
      this.categories.set([]);
      return;
    }
    const categories = this.service.categories(list.id).pipe(
      catchError((error: unknown) => {
        this.error.set(apiErrorMessage(error, this.i18n.t('grocery.categories.not_loaded')));
        return of([]);
      }),
    );
    this.loadingItems = forkJoin([this.service.items(list.id, this.showBought()), categories])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ([items, categories]) => {
          // a field of an item that is gone closes (GROCERY-14)
          const renaming = this.renaming();
          if (renaming !== null && !items.some((each) => each.id === renaming)) {
            this.closeRename(renaming);
          }
          this.items.set(items);
          this.categories.set(categories);
          this.loaded.set(true);
        },
        error: (error: unknown) =>
          this.error.set(apiErrorMessage(error, this.i18n.t('grocery.list.items_not_loaded'))),
      });
  }
}
