import {
  afterNextRender,
  Component,
  computed,
  CUSTOM_ELEMENTS_SCHEMA,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  CdkDrag,
  CdkDragDrop,
  CdkDragHandle,
  CdkDropList,
  moveItemInArray,
} from '@angular/cdk/drag-drop';
import {
  catchError,
  concatMap,
  debounceTime,
  defer,
  EMPTY,
  filter,
  finalize,
  Observable,
  Subject,
  tap,
} from 'rxjs';
import { I18n, TextKey, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { ListSettingsForm } from '../shared/list-settings-form';
import { SharedList } from '../shared/list-service';
import { RowError } from '../shared/row-error';
import { ActionMenu } from '../shared/action-menu';
import { GroceryCategory, GroceryService } from './grocery.service';
import { CHANGES_DEBOUNCE_MS, EVERYTHING, GroceryChanges } from './grocery-changes';

// the settings of a grocery list (ListSettingsForm), with its categories below them once the list
// is loaded (GROCERY-13): each change of them is saved at once, not with the form, one after the
// other in the order they were made; a change of the list from elsewhere, or a change refused, loads
// them again. A category is moved by dragging its grip (@angular/cdk/drag-drop), or one place up or
// down from the menu of its row, which a keyboard reaches
@Component({
  selector: 'app-grocery-list-form',
  imports: [ActionMenu, CdkDrag, CdkDragHandle, CdkDropList, ListSettingsForm, TranslatePipe],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  host: { class: 'd-block' },
  template: `
    <app-list-settings-form [id]="id()" [from]="from()" (loaded)="opened($event)">
      @if (list()) {
        <section class="card card-body mt-4" aria-labelledby="categories-title">
          <h2 class="h5" id="categories-title">{{ 'grocery.categories.title' | t }}</h2>
          <p class="form-text mt-0">{{ 'grocery.categories.hint' | t }}</p>
          @if (categoriesError() ?? strayCategoryError(); as error) {
            <p class="alert alert-danger" role="alert">{{ error }}</p>
          }
          @if (categories().length) {
            <ul
              class="list-group mb-3"
              cdkDropList
              cdkDropListLockAxis="y"
              [attr.aria-label]="'grocery.categories.title' | t"
              (cdkDropListDropped)="dropped($event)"
            >
              @for (category of categories(); track category.id; let first = $first, last = $last) {
                <li
                  class="list-group-item category-row"
                  cdkDrag
                  [attr.data-testid]="'category-' + category.id"
                >
                  <!-- one group, so the icon and the name have one height -->
                  <div class="d-flex align-items-start gap-2">
                    <span
                      class="category-handle align-self-center text-body-secondary"
                      cdkDragHandle
                      [title]="'grocery.categories.drag' | t: { name: category.name }"
                    >
                      <i class="fa-solid fa-grip-vertical" aria-hidden="true"></i>
                      <span class="visually-hidden">{{
                        'grocery.categories.drag' | t: { name: category.name }
                      }}</span>
                    </span>
                    <div class="input-group has-validation flex-grow-1 min-w-0">
                      <button
                        type="button"
                        class="btn btn-outline-secondary btn-field category-icon-button"
                        [attr.aria-label]="
                          'grocery.categories.icon_of' | t: { name: category.name }
                        "
                        [attr.aria-expanded]="pickingFor() === category.id"
                        (click)="togglePickerOf(category)"
                      >
                        @if (category.icon) {
                          {{ category.icon }}
                        } @else {
                          <i class="fa-regular fa-face-smile" aria-hidden="true"></i>
                        }
                      </button>
                      <input
                        #categoryName
                        class="form-control"
                        maxlength="32"
                        autocomplete="off"
                        [class.is-invalid]="unnamed().has(category.id)"
                        [value]="category.name"
                        [attr.aria-label]="
                          'grocery.categories.name_of' | t: { name: category.name }
                        "
                        (input)="named(category.id)"
                        (blur)="rename(category, categoryName)"
                        (keydown.enter)="$event.preventDefault(); categoryName.blur()"
                      />
                      <!-- always there, shown by is-invalid: has-validation squares the corners of
                           the icon only when two elements follow it -->
                      <div class="invalid-feedback">
                        {{ 'grocery.categories.name_required' | t }}
                      </div>
                    </div>
                    <app-action-menu
                      [name]="category.name"
                      deletes
                      [deleteDisabled]="busy() ? ('grocery.categories.saving' | t) : null"
                      (deleted)="deleteCategory(category)"
                    >
                      <button
                        type="button"
                        class="dropdown-item"
                        role="menuitem"
                        [disabled]="first"
                        [title]="first ? ('grocery.categories.first' | t) : ''"
                        (click)="move(category.id, -1)"
                      >
                        <i class="fa-solid fa-arrow-up me-2" aria-hidden="true"></i
                        >{{ 'grocery.categories.move_up' | t }}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item"
                        role="menuitem"
                        [disabled]="last"
                        [title]="last ? ('grocery.categories.last' | t) : ''"
                        (click)="move(category.id, 1)"
                      >
                        <i class="fa-solid fa-arrow-down me-2" aria-hidden="true"></i
                        >{{ 'grocery.categories.move_down' | t }}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item"
                        role="menuitem"
                        [disabled]="!category.icon"
                        [title]="category.icon ? '' : ('grocery.categories.no_icon_yet' | t)"
                        (click)="iconOf(category, null)"
                      >
                        <i class="fa-solid fa-xmark me-2" aria-hidden="true"></i
                        >{{ 'grocery.form.no_icon' | t }}
                      </button>
                    </app-action-menu>
                  </div>
                  @if (pickingFor() === category.id) {
                    <emoji-picker
                      class="mt-2"
                      data-source="/emoji/data.json"
                      (emoji-click)="iconOf(category, $any($event).detail.unicode)"
                    ></emoji-picker>
                  }
                  <!-- the error of a change of the category, in its row (UX-15) -->
                  @if (categoryError.of(category.id); as error) {
                    <p class="alert alert-danger mt-2 mb-0" role="alert">{{ error }}</p>
                  }
                </li>
              }
            </ul>
          } @else if (categoriesLoaded()) {
            <p class="text-body-secondary" data-testid="no-categories">
              {{ 'grocery.categories.none' | t }}
            </p>
          }
          <form (submit)="$event.preventDefault(); addCategory()">
            <label class="form-label" for="new-category">{{ 'grocery.categories.new' | t }}</label>
            <div class="input-group has-validation">
              <input
                #newCategory
                id="new-category"
                class="form-control"
                maxlength="32"
                autocomplete="off"
                [class.is-invalid]="newInvalid()"
                [value]="newName()"
                (input)="newName.set(newCategory.value); newInvalid.set(false)"
                (blur)="newInvalid.set(newName() !== '' && !newName().trim())"
              />
              <button
                class="btn btn-outline-secondary btn-field-primary"
                type="submit"
                [disabled]="!newName().trim()"
              >
                <i class="fa-solid fa-tag" aria-hidden="true"></i>
                {{ 'grocery.categories.add' | t }}
              </button>
              <!-- always there, shown by is-invalid, so the field keeps square corners at the button -->
              <div class="invalid-feedback">{{ 'grocery.categories.name_required' | t }}</div>
            </div>
            @if (categoryError.of('new'); as error) {
              <p class="alert alert-danger mt-2 mb-0" role="alert">{{ error }}</p>
            }
          </form>
        </section>
      }
    </app-list-settings-form>
  `,
})
export class GroceryListForm {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(GroceryService);
  private readonly i18n = inject(I18n);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly injector = inject(Injector);

  // the changes of the categories, each a request made when its turn comes, one after the other
  private readonly queue = new Subject<() => Observable<unknown>>();
  private readonly pending = signal(0);

  // the route parameter, missing for a new list
  readonly id = input<string | undefined>();
  // the query parameter: lists when the settings were opened from all lists, they lead back there
  readonly from = input<string | undefined>();

  // the list once the settings loaded it, null for a new list
  protected readonly list = signal<SharedList | null>(null);

  // the categories of the list, in their order (GROCERY-13)
  protected readonly categories = signal<GroceryCategory[]>([]);
  protected readonly categoriesLoaded = signal(false);
  // the categories could not be loaded; a change of one failed, in its row or, adding one, under
  // the field of the new one (UX-15)
  protected readonly categoriesError = signal<string | null>(null);
  protected readonly categoryError = new RowError<number | 'new'>();
  protected strayCategoryError(): string | null {
    return this.categoryError.stray(
      (at) => at === 'new' || this.categories().some((category) => category.id === at),
    );
  }
  // a change of the categories on its way, or one waiting for it
  protected readonly busy = computed(() => this.pending() > 0);
  // the categories whose name field was left empty (UX-6)
  protected readonly unnamed = signal<ReadonlySet<number>>(new Set());
  // the category whose icon is being chosen
  protected readonly pickingFor = signal<number | null>(null);
  // the name of the next category and whether it was left without one
  protected readonly newName = signal('');
  protected readonly newInvalid = signal(false);

  constructor() {
    this.queue
      .pipe(
        concatMap((step) => step()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
    // a change of the list from elsewhere, or of everything after the stream was away (LIST-4)
    inject(GroceryChanges)
      .changes()
      .pipe(
        filter((id) => this.list() !== null && (id === EVERYTHING || id === this.list()?.id)),
        debounceTime(CHANGES_DEBOUNCE_MS),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.loadCategories());
  }

  protected opened(list: SharedList): void {
    this.list.set(list);
    this.loadCategories();
  }

  // the field of the category is typed in again: its error goes until it is left (UX-6)
  protected named(id: number): void {
    this.unnamed.update((ids) => new Set([...ids].filter((each) => each !== id)));
  }

  // the name of the category once its field is left; an empty one is not saved, an unchanged one
  // shows as it is stored
  protected rename(category: GroceryCategory, field: HTMLInputElement): void {
    const name = field.value.trim();
    if (!name) {
      this.unnamed.update((ids) => new Set([...ids, category.id]));
      return;
    }
    if (name === category.name) {
      field.value = category.name;
      return;
    }
    this.saveCategory(
      category.id,
      { name },
      () => (field.value = this.stored(category.id)?.name ?? ''),
    );
  }

  // the picker is a custom element, defined once it is first opened
  protected async togglePickerOf(category: GroceryCategory): Promise<void> {
    if (this.pickingFor() !== category.id) await import('emoji-picker-element');
    this.pickingFor.update((id) => (id === category.id ? null : category.id));
  }

  protected iconOf(category: GroceryCategory, icon: string | null | undefined): void {
    if (icon === undefined) return;
    this.pickingFor.set(null);
    this.saveCategory(category.id, { icon });
  }

  // the category one place up (-1) or down (1) of where it is when its turn comes, from the menu of
  // its row; the focus goes back to that menu, so a keyboard moves it on
  protected move(id: number, by: -1 | 1): void {
    this.change(
      (listId) => {
        const ids = this.categories().map((category) => category.id);
        const index = ids.indexOf(id);
        const other = index + by;
        if (index < 0 || other < 0 || other >= ids.length) return EMPTY;
        [ids[index], ids[other]] = [ids[other], ids[index]];
        return this.service.orderCategories(listId, ids);
      },
      (ordered) => {
        this.categories.set(this.inOrderOf(ordered));
        this.focusMenu(id);
      },
      'grocery.categories.not_ordered',
      id,
    );
  }

  // a category dropped at another place: the page shows it there at once; when its turn comes, it
  // goes to that place among the categories as they are then, so a change before it is kept
  protected dropped(event: Pick<CdkDragDrop<unknown>, 'previousIndex' | 'currentIndex'>): void {
    if (event.previousIndex === event.currentIndex) return;
    const id = this.categories()[event.previousIndex]?.id;
    if (id === undefined) return;
    const moved = [...this.categories()];
    moveItemInArray(moved, event.previousIndex, event.currentIndex);
    this.categories.set(moved);
    this.change(
      (listId) => {
        const ids = this.categories()
          .map((category) => category.id)
          .filter((each) => each !== id);
        if (ids.length === this.categories().length) return EMPTY;
        ids.splice(Math.min(event.currentIndex, ids.length), 0, id);
        return this.service.orderCategories(listId, ids);
      },
      (ordered) => this.categories.set(this.inOrderOf(ordered)),
      'grocery.categories.not_ordered',
      id,
    );
  }

  protected addCategory(): void {
    const name = this.newName().trim();
    if (!name) return;
    this.newName.set('');
    this.change(
      (listId) => this.service.createCategory(listId, name, null),
      (category) => this.categories.update((categories) => [...categories, category]),
      'grocery.categories.not_saved',
      'new',
      () => this.newName.set(name),
    );
  }

  // its items lose it; asked first in the menu of its row (UX-12)
  protected deleteCategory(category: GroceryCategory): void {
    this.change(
      (listId) => this.service.deleteCategory(listId, category.id),
      () =>
        this.categories.update((categories) =>
          categories.filter((each) => each.id !== category.id),
        ),
      'grocery.categories.not_deleted',
      category.id,
    );
  }

  // the name or the icon of a category; the other one as it is when its turn comes, so changes
  // made one after the other do not undo each other
  private saveCategory(
    id: number,
    values: { name?: string; icon?: string | null },
    failed?: () => void,
  ): void {
    this.change(
      (listId) => {
        const stored = this.stored(id);
        if (!stored) return EMPTY;
        return this.service.updateCategory(
          listId,
          id,
          values.name ?? stored.name,
          values.icon === undefined ? (stored.icon ?? null) : values.icon,
        );
      },
      (changed) =>
        this.categories.update((categories) =>
          categories.map((each) => (each.id === changed.id ? changed : each)),
        ),
      'grocery.categories.not_saved',
      id,
      failed,
    );
  }

  // a change of the categories, after the ones before it; a refused one says why where it was made
  // (at: the category, or new for the one added) and loads the categories again, so the next
  // changes start from what is stored
  private change<T>(
    request: (listId: number) => Observable<T>,
    done: (result: T) => void,
    failure: TextKey,
    at: number | 'new',
    failed?: () => void,
  ): void {
    const listId = this.list()?.id;
    if (listId === undefined) return;
    this.pending.update((count) => count + 1);
    this.queue.next(() =>
      defer(() => {
        this.categoryError.clear();
        return request(listId);
      }).pipe(
        tap((result) => done(result)),
        catchError((error: unknown) => {
          failed?.();
          this.categoryError.set(at, apiErrorMessage(error, this.i18n.t(failure)));
          return this.reload(listId);
        }),
        finalize(() => this.pending.update((count) => count - 1)),
      ),
    );
  }

  // the categories as they are stored, after the changes made before
  private loadCategories(): void {
    const listId = this.list()?.id;
    if (listId === undefined) return;
    this.pending.update((count) => count + 1);
    this.queue.next(() =>
      this.reload(listId).pipe(finalize(() => this.pending.update((count) => count - 1))),
    );
  }

  private reload(listId: number): Observable<unknown> {
    return this.service.categories(listId).pipe(
      tap((categories) => {
        this.categories.set(categories);
        this.categoriesLoaded.set(true);
        this.categoriesError.set(null);
      }),
      catchError((error: unknown) => {
        this.categoriesError.set(
          apiErrorMessage(error, this.i18n.t('grocery.categories.not_loaded')),
        );
        return EMPTY;
      }),
    );
  }

  // the categories in the order of the answer, with the names and icons as they are here: a change
  // of them that came after the order is kept
  private inOrderOf(ordered: GroceryCategory[]): GroceryCategory[] {
    const current = new Map(this.categories().map((category) => [category.id, category]));
    return ordered.map((category) => current.get(category.id) ?? category);
  }

  private stored(id: number): GroceryCategory | undefined {
    return this.categories().find((category) => category.id === id);
  }

  // the ⋮ of the row of the category
  private focusMenu(id: number): void {
    afterNextRender(
      () =>
        (this.host.nativeElement as HTMLElement)
          .querySelector<HTMLButtonElement>(
            `[data-testid="category-${id}"] app-action-menu button[aria-haspopup]`,
          )
          ?.focus(),
      { injector: this.injector },
    );
  }
}
