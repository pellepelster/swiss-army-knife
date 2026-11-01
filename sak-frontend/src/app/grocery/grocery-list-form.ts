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
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
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
import { ConfirmDelete } from '../shared/confirm-delete';
import { apiErrorMessage } from '../shared/api';
import { GroceryCategory, GroceryList, GroceryService } from './grocery.service';
import { ListIcon } from './list-icon';
import { PageHeader } from '../shared/page-header';
import { ActionMenu } from '../shared/action-menu';
import { CHANGES_DEBOUNCE_MS, EVERYTHING, GroceryChanges } from './grocery-changes';

// creates a grocery list, or changes the settings of one of the user (its name, whether it is
// shared, its icon) and deletes it, on a page of its own. Afterwards the list shows. The icon is one
// emoji, chosen in the picker of emoji-picker-element, loaded when it is opened; its data comes
// from the frontend itself (/emoji/data.json), not from a cdn. The settings of a list also hold its
// categories (GROCERY-13): each change of them is saved at once, not with the form, one after the
// other in the order they were made; a change of the list from elsewhere, or a change refused, loads
// them again
@Component({
  selector: 'app-grocery-list-form',
  imports: [ActionMenu, PageHeader, ConfirmDelete, ListIcon, RouterLink, TranslatePipe],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader [back]="back()" [backLabel]="'grocery.back' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">
        {{ (id() ? 'shared.settings' : 'grocery.form.create') | t }}
      </h1>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (!id() || list()) {
      <form class="vstack gap-3 card card-body" (submit)="$event.preventDefault(); save()">
        <div>
          <label class="form-label" for="list-name">{{ 'shared.name' | t }}</label>
          <input
            #nameField
            id="list-name"
            class="form-control"
            maxlength="64"
            autocomplete="off"
            [value]="name()"
            (input)="name.set(nameField.value)"
          />
        </div>
        <div>
          <span class="form-label d-block" id="list-icon-label">{{ 'grocery.form.icon' | t }}</span>
          <div class="d-flex flex-wrap align-items-center gap-2">
            <app-list-icon [name]="name()" [icon]="icon()" [size]="48" data-testid="list-icon" />
            <div class="btn-group" role="group" aria-labelledby="list-icon-label">
              <button
                type="button"
                class="btn btn-outline-secondary"
                [attr.aria-expanded]="picking()"
                (click)="togglePicker()"
              >
                {{ 'grocery.form.choose_icon' | t }}
              </button>
              <button
                type="button"
                class="btn btn-outline-secondary"
                [disabled]="!icon()"
                (click)="icon.set(null)"
              >
                {{ 'grocery.form.no_icon' | t }}
              </button>
            </div>
          </div>
          @if (picking()) {
            <emoji-picker
              class="mt-2"
              data-source="/emoji/data.json"
              (emoji-click)="choose($any($event).detail.unicode)"
            ></emoji-picker>
          }
        </div>
        <div class="form-check form-switch">
          <input
            #sharedInput
            id="list-shared"
            class="form-check-input"
            type="checkbox"
            role="switch"
            [checked]="shared()"
            (change)="shared.set(sharedInput.checked)"
          />
          <label class="form-check-label" for="list-shared">{{ 'grocery.form.shared' | t }}</label>
          <div class="form-text">{{ 'grocery.form.shared_hint' | t }}</div>
        </div>

        <!-- one group of the actions; a delete asks inline in its place (UX-9) -->
        <div class="d-flex flex-wrap align-items-center justify-content-end gap-2">
          @if (confirming()) {
            <app-confirm-delete
              [name]="list()?.name ?? ''"
              (confirmed)="delete()"
              (kept)="confirming.set(false)"
            />
          } @else {
            <div class="btn-group" role="group" [attr.aria-label]="'shared.form_actions' | t">
              @if (list()) {
                <button
                  type="button"
                  class="btn btn-outline-danger"
                  [disabled]="last()"
                  (click)="confirming.set(true)"
                >
                  {{ 'grocery.form.delete_list' | t }}
                </button>
              }
              <a class="btn btn-outline-secondary" [routerLink]="back()">{{
                'shared.cancel' | t
              }}</a>
              <button class="btn btn-primary" type="submit" [disabled]="!name().trim() || saving()">
                @if (saving()) {
                  <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
                } @else if (!id()) {
                  <i class="sak-icon-plus" aria-hidden="true"></i>
                }
                {{ (id() ? 'shared.save' : 'grocery.form.create') | t }}
              </button>
            </div>
          }
        </div>
        @if (last()) {
          <div class="form-text text-end" data-testid="last-list">
            {{ 'grocery.form.last_list' | t }}
          </div>
        }
      </form>
    }

    @if (list()) {
      <section class="card card-body mt-4" aria-labelledby="categories-title">
        <h2 class="h5" id="categories-title">{{ 'grocery.categories.title' | t }}</h2>
        <p class="form-text mt-0">{{ 'grocery.categories.hint' | t }}</p>
        @if (categoryError(); as error) {
          <p class="alert alert-danger" role="alert">{{ error }}</p>
        }
        @if (categories().length) {
          <ul class="list-group mb-3" [attr.aria-label]="'grocery.categories.title' | t">
            @for (category of categories(); track category.id; let first = $first, last = $last) {
              <li class="list-group-item" [attr.data-testid]="'category-' + category.id">
                <!-- one group, so the icon, the name and the order have one height -->
                <div class="d-flex align-items-start gap-2">
                  <div class="input-group has-validation flex-grow-1 min-w-0">
                    <button
                      type="button"
                      class="btn btn-outline-secondary category-icon-button"
                      [attr.aria-label]="'grocery.categories.icon_of' | t: { name: category.name }"
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
                      [attr.aria-label]="'grocery.categories.name_of' | t: { name: category.name }"
                      (input)="named(category.id)"
                      (blur)="rename(category, categoryName)"
                      (keydown.enter)="$event.preventDefault(); categoryName.blur()"
                    />
                    <button
                      type="button"
                      class="btn btn-outline-secondary"
                      [attr.aria-label]="'grocery.categories.up' | t: { name: category.name }"
                      [title]="'grocery.categories.up' | t: { name: category.name }"
                      [attr.data-move]="'up-' + category.id"
                      [disabled]="first"
                      (click)="move(category.id, -1)"
                    >
                      <i class="fa-solid fa-arrow-up" aria-hidden="true"></i>
                    </button>
                    <button
                      type="button"
                      class="btn btn-outline-secondary"
                      [attr.aria-label]="'grocery.categories.down' | t: { name: category.name }"
                      [title]="'grocery.categories.down' | t: { name: category.name }"
                      [attr.data-move]="'down-' + category.id"
                      [disabled]="last"
                      (click)="move(category.id, 1)"
                    >
                      <i class="fa-solid fa-arrow-down" aria-hidden="true"></i>
                    </button>
                    @if (unnamed().has(category.id)) {
                      <div class="invalid-feedback">
                        {{ 'grocery.categories.name_required' | t }}
                      </div>
                    }
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
            <button class="btn btn-outline-primary" type="submit" [disabled]="!newName().trim()">
              <i class="fa-solid fa-tag" aria-hidden="true"></i>
              {{ 'grocery.categories.add' | t }}
            </button>
            @if (newInvalid()) {
              <div class="invalid-feedback">{{ 'grocery.categories.name_required' | t }}</div>
            }
          </div>
        </form>
      </section>
    }
  `,
})
export class GroceryListForm implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(GroceryService);
  private readonly router = inject(Router);
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

  protected readonly list = signal<GroceryList | null>(null);
  protected readonly name = signal('');
  protected readonly shared = signal(false);
  // one emoji, null for none
  protected readonly icon = signal<string | null>(null);
  protected readonly picking = signal(false);
  protected readonly saving = signal(false);
  protected readonly confirming = signal(false);
  // the only list of the user's own, which can not be deleted (LIST-1)
  protected readonly last = signal(false);
  protected readonly error = signal<string | null>(null);

  // the categories of the list, in their order (GROCERY-13)
  protected readonly categories = signal<GroceryCategory[]>([]);
  protected readonly categoriesLoaded = signal(false);
  protected readonly categoryError = signal<string | null>(null);
  // a change of the categories on its way, or one waiting for it
  protected readonly busy = computed(() => this.pending() > 0);
  // the categories whose name field was left empty (UX-6)
  protected readonly unnamed = signal<ReadonlySet<number>>(new Set());
  // the category whose icon is being chosen
  protected readonly pickingFor = signal<number | null>(null);
  // the name of the next category and whether it was left without one
  protected readonly newName = signal('');
  protected readonly newInvalid = signal(false);

  // where the settings were opened: the list edited, or all lists for a new one and from there
  protected readonly back = computed(() => {
    const id = this.id();
    return id && this.from() !== 'lists' ? ['/grocery/lists', id] : ['/grocery'];
  });

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

  ngOnInit(): void {
    const id = this.id();
    if (!id) return;
    this.service
      .lists()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (lists) => {
          const list = lists.find((each) => String(each.id) === id && each.mine);
          if (!list) {
            this.error.set(this.i18n.t('grocery.form.no_such_list'));
            return;
          }
          this.list.set(list);
          this.last.set(lists.filter((each) => each.mine).length === 1);
          this.name.set(list.name);
          this.shared.set(list.shared);
          this.icon.set(list.icon);
          this.loadCategories();
        },
        error: (error: unknown) =>
          this.error.set(apiErrorMessage(error, this.i18n.t('grocery.form.not_loaded'))),
      });
  }

  protected save(): void {
    const id = this.list()?.id;
    this.saving.set(true);
    this.error.set(null);
    const saved =
      id === undefined
        ? this.service.createList(this.name().trim(), this.shared(), this.icon())
        : this.service.updateList(id, this.name().trim(), this.shared(), this.icon());
    saved.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      // a new list opens, a changed one shows where its settings were opened
      next: (list) =>
        void this.router.navigate(id === undefined ? ['/grocery/lists', list.id] : this.back()),
      error: (error: unknown) => {
        this.saving.set(false);
        this.error.set(apiErrorMessage(error, this.i18n.t('grocery.form.not_saved')));
      },
    });
  }

  // the picker is a custom element, defined once it is first opened
  protected async togglePicker(): Promise<void> {
    if (!this.picking()) await import('emoji-picker-element');
    this.picking.update((picking) => !picking);
  }

  protected choose(emoji: string | undefined): void {
    if (!emoji) return;
    this.icon.set(emoji);
    this.picking.set(false);
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

  // the category one place up (-1) or down (1) of where it is when its turn comes; the focus stays
  // on the arrow clicked, or goes to the other one at an end (keyboard users move it on)
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
        this.focusArrow(id, by);
      },
      'grocery.categories.not_ordered',
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
          values.icon === undefined ? stored.icon : values.icon,
        );
      },
      (changed) =>
        this.categories.update((categories) =>
          categories.map((each) => (each.id === changed.id ? changed : each)),
        ),
      'grocery.categories.not_saved',
      failed,
    );
  }

  // a change of the categories, after the ones before it; a refused one says why and loads the
  // categories again, so the next changes start from what is stored
  private change<T>(
    request: (listId: number) => Observable<T>,
    done: (result: T) => void,
    failure: TextKey,
    failed?: () => void,
  ): void {
    const listId = this.list()?.id;
    if (listId === undefined) return;
    this.pending.update((count) => count + 1);
    this.queue.next(() =>
      defer(() => {
        this.categoryError.set(null);
        return request(listId);
      }).pipe(
        tap((result) => done(result)),
        catchError((error: unknown) => {
          failed?.();
          this.categoryError.set(apiErrorMessage(error, this.i18n.t(failure)));
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
      }),
      catchError((error: unknown) => {
        this.categoryError.set(
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

  private focusArrow(id: number, by: -1 | 1): void {
    afterNextRender(
      () => {
        const element = this.host.nativeElement as HTMLElement;
        const same = element.querySelector<HTMLButtonElement>(
          `[data-move="${by < 0 ? 'up' : 'down'}-${id}"]`,
        );
        const other = element.querySelector<HTMLButtonElement>(
          `[data-move="${by < 0 ? 'down' : 'up'}-${id}"]`,
        );
        (same && !same.disabled ? same : other)?.focus();
      },
      { injector: this.injector },
    );
  }

  protected delete(): void {
    const id = this.list()?.id;
    if (id === undefined) return;
    this.confirming.set(false);
    this.service
      .deleteList(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigate(['/grocery']),
        error: (error: unknown) =>
          this.error.set(apiErrorMessage(error, this.i18n.t('grocery.form.not_deleted'))),
      });
  }
}
