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
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, filter, firstValueFrom, Observable, Subscription } from 'rxjs';
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
      <!-- the photo shows the photo in full, a tap on the rest of the row checks the item
           (GROCERY-3), struck through while it can be undone; the menu attaches a photo -->
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
        <button
          type="button"
          class="btn grocery-item flex-grow-1 min-w-0 rounded-0 border-0 text-start d-flex flex-wrap align-items-center gap-2 px-3 py-2"
          [id]="'item-' + item.id"
          [attr.aria-pressed]="struck().has(item.id)"
          (click)="check(item)"
        >
          <span
            class="item-name flex-grow-1 text-break"
            [class.text-decoration-line-through]="struck().has(item.id)"
            [class.text-body-secondary]="struck().has(item.id)"
          >
            {{ item.name }}
            @if (item.amount !== null) {
              <span class="text-body-secondary">· {{ item.amount }} {{ item.unit ?? '' }}</span>
            }
          </span>
        </button>
        <app-action-menu class="pe-1" [name]="item.name">
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
        </app-action-menu>
      </li>
    </ng-template>

    <!-- the photo chosen for the item of photoFor, from the camera or the files -->
    <input
      #photoInput
      type="file"
      accept="image/*"
      capture="environment"
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
  protected readonly viewing = signal<{ name: string; url: string | null } | null>(null);

  protected async viewPhoto(item: GroceryItem): Promise<void> {
    this.viewing.set({ name: item.name, url: null });
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('.photo-viewer')?.focus(),
      {
        injector: this.injector,
      },
    );
    try {
      const url = await this.photos.full(item);
      if (this.viewing()?.name === item.name && this.viewing()?.url === null) {
        this.viewing.set({ name: item.name, url });
      } else {
        URL.revokeObjectURL(url);
      }
    } catch (error: unknown) {
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
