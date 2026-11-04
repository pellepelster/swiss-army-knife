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
import { amountText, parseAmount, UnitOption, unitOptions } from './grocery-quantity';
import { GroceryPhotos, PHOTO_IN_ROW, PhotoInRowPipe } from './grocery-photos';
import { PhotoScaler } from '../notes/photo-scaler';
import { ActionMenu } from '../shared/action-menu';
import { CHANGES_DEBOUNCE_MS, EVERYTHING, GroceryChanges } from './grocery-changes';
import {
  GroceryCategory,
  GroceryDiscoveredItem,
  GroceryItem,
  GroceryList,
  GroceryService,
  GrocerySuggestion,
} from './grocery.service';
import { PageHeader } from '../shared/page-header';
import { RowError } from '../shared/row-error';

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

// one item the AI read in a photo, as the user corrects it before it is added (GROCERY-16); the
// errors show once a field is left or the items are added
interface DiscoveredRow {
  key: number;
  include: boolean;
  name: string;
  amount: string;
  unit: string;
  // the units to choose, the one the AI read among them
  units: UnitOption[];
  nameEmpty: boolean;
  amountInvalid: boolean;
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
  // the number of a quantity wide enough for its longest one, the unit as wide as its label
  styles: `
    .quantity-amount {
      width: 8rem;
    }
    .quantity-unit {
      width: auto;
    }
  `,
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
        <!-- every user who sees a list changes its settings (LIST-2) -->
        @if (shown(); as list) {
          <a class="btn btn-outline-secondary" [routerLink]="['/grocery/lists', list.id, 'edit']"
            ><i class="fa-solid fa-gear" aria-hidden="true"></i> {{ 'shared.settings' | t }}</a
          >
        }
      </div>
    </header>

    @if (listsError() ?? itemsError() ?? strayItemError(); as error) {
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
          <!-- the robot reads the items in a photo, only with the AI features turned on; its label
               names OpenRouter, where the photo goes (GROCERY-16, PRIVACY-2) -->
          @if (discoverItems()) {
            <button
              type="button"
              class="btn btn-outline-secondary btn-field-primary"
              data-testid="discover"
              [attr.aria-label]="'grocery.discover.label' | t"
              [title]="'grocery.discover.label' | t"
              [disabled]="discovering()"
              (click)="chooseDiscoverPhoto()"
            >
              <i class="fa-solid fa-robot" aria-hidden="true"></i>
            </button>
          }
          <!-- only the icon of what it adds, its label and tooltip say what it does (UX-2) -->
          <button
            class="btn btn-primary"
            type="submit"
            [attr.aria-label]="'grocery.list.add_item' | t"
            [title]="'grocery.list.add_item' | t"
            [disabled]="!itemName().trim() || adding()"
          >
            @if (adding()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            } @else {
              <i class="fa-solid fa-cart-shopping" aria-hidden="true"></i>
            }
          </button>
        </div>
        @if (discovering()) {
          <p class="mt-2 mb-0 text-body-secondary" role="status" data-testid="discover-reading">
            <i class="fa-solid fa-spinner fa-spin me-2" aria-hidden="true"></i
            >{{ 'grocery.discover.reading' | t }}
          </p>
        }
        <!-- the error of adding the item or of reading the photo, under the field (UX-15) -->
        @if (addError(); as error) {
          <p class="alert alert-danger mt-2 mb-0" role="alert">{{ error }}</p>
        }
        <datalist id="grocery-suggestions">
          @for (suggestion of suggestions(); track suggestion.id) {
            <option [value]="suggestion.name"></option>
          }
        </datalist>
      </form>

      <!-- the items read in the photo, in the page, not in a dialog (WEB-3): corrected, left out or
           added at once; cancel adds none (GROCERY-16) -->
      @if (discovered(); as rows) {
        @if (rows.length) {
          <form
            id="discovered"
            class="card card-body mb-4"
            tabindex="-1"
            aria-labelledby="discovered-title"
            data-testid="discovered"
            (submit)="$event.preventDefault(); addDiscovered()"
          >
            <!-- nothing changes while the items are added, their answers would undo it -->
            <fieldset class="border-0 p-0 m-0" [disabled]="addingDiscovered()">
              <h2 class="h5" id="discovered-title">{{ 'grocery.discover.title' | t }}</h2>
              <p class="text-body-secondary">{{ 'grocery.discover.hint' | t }}</p>
              <ul class="list-unstyled mb-0">
                @for (row of rows; track row.key; let index = $index) {
                  <li
                    class="d-flex flex-wrap align-items-start gap-2 mb-2"
                    [attr.data-testid]="'discovered-' + index"
                  >
                    <input
                      type="checkbox"
                      class="form-check-input flex-shrink-0 mt-2"
                      [checked]="row.include"
                      [attr.aria-label]="'grocery.discover.include' | t: { name: row.name }"
                      (change)="changeDiscovered(row.key, { include: $any($event.target).checked })"
                    />
                    <div class="flex-grow-1 min-w-0">
                      <input
                        class="form-control"
                        maxlength="128"
                        autocomplete="off"
                        required
                        [id]="'discovered-name-' + row.key"
                        [class.is-invalid]="row.include && row.nameEmpty"
                        [attr.aria-label]="'grocery.discover.name_of' | t: { number: index + 1 }"
                        [attr.aria-describedby]="
                          row.include && row.nameEmpty ? 'discovered-name-error-' + row.key : null
                        "
                        [value]="row.name"
                        (input)="
                          changeDiscovered(row.key, {
                            name: $any($event.target).value,
                            nameEmpty: false,
                          })
                        "
                        (blur)="
                          changeDiscovered(row.key, {
                            nameEmpty: !$any($event.target).value.trim(),
                          })
                        "
                      />
                      @if (row.include && row.nameEmpty) {
                        <div
                          class="invalid-feedback d-block"
                          [id]="'discovered-name-error-' + row.key"
                        >
                          {{ 'grocery.list.name_required' | t }}
                        </div>
                      }
                    </div>
                    <div
                      class="d-flex align-items-start gap-2"
                      role="group"
                      [attr.aria-label]="'grocery.quantity.of' | t: { name: row.name }"
                    >
                      <div class="quantity-amount">
                        <input
                          class="form-control"
                          inputmode="decimal"
                          autocomplete="off"
                          [id]="'discovered-amount-' + row.key"
                          [class.is-invalid]="row.include && row.amountInvalid"
                          [attr.aria-label]="'grocery.quantity.amount' | t"
                          [attr.aria-describedby]="
                            row.include && row.amountInvalid
                              ? 'discovered-amount-error-' + row.key
                              : null
                          "
                          [value]="row.amount"
                          (input)="
                            changeDiscovered(row.key, {
                              amount: $any($event.target).value,
                              amountInvalid: false,
                            })
                          "
                          (blur)="
                            changeDiscovered(row.key, {
                              amountInvalid: parseAmount($any($event.target).value) === undefined,
                            })
                          "
                        />
                        @if (row.include && row.amountInvalid) {
                          <div
                            class="invalid-feedback d-block"
                            [id]="'discovered-amount-error-' + row.key"
                          >
                            {{ 'grocery.quantity.invalid' | t }}
                          </div>
                        }
                      </div>
                      <select
                        class="form-select quantity-unit"
                        [id]="'discovered-unit-' + row.key"
                        [attr.aria-label]="'grocery.quantity.unit' | t"
                        (change)="changeDiscovered(row.key, { unit: $any($event.target).value })"
                      >
                        <option value="" [selected]="row.unit === ''">
                          {{ 'grocery.quantity.no_unit' | t }}
                        </option>
                        @for (unit of row.units; track unit.value) {
                          <option [value]="unit.value" [selected]="unit.value === row.unit">
                            {{ unit.label }}
                          </option>
                        }
                      </select>
                    </div>
                  </li>
                }
              </ul>
              @if (discoveredError(); as error) {
                <p class="alert alert-danger mt-3 mb-0" role="alert">{{ error }}</p>
              }
              <div class="d-flex justify-content-end gap-2 mt-3">
                <button type="button" class="btn btn-outline-secondary" (click)="closeDiscovered()">
                  {{ 'shared.cancel' | t }}
                </button>
                <button type="submit" class="btn btn-primary" [disabled]="!canAddDiscovered()">
                  @if (addingDiscovered()) {
                    <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
                  } @else {
                    <i class="fa-solid fa-cart-shopping" aria-hidden="true"></i>
                  }
                  {{ 'grocery.discover.add' | t }}
                </button>
              </div>
            </fieldset>
          </form>
        } @else {
          <!-- nothing to choose from: only a warning, acknowledged with OK -->
          <div
            id="discovered"
            class="alert alert-warning d-flex align-items-center gap-3 mb-4"
            tabindex="-1"
            role="status"
            data-testid="discovered"
          >
            <span class="me-auto">{{ 'grocery.discover.none' | t }}</span>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              (click)="closeDiscovered()"
            >
              {{ 'shared.ok' | t }}
            </button>
          </div>
        }
      }

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
        class="list-group-item p-0 d-flex flex-wrap align-items-center"
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
        } @else if (quantityOf() === item.id) {
          <!-- the quantity in fields, a number and a unit (GROCERY-15): Enter or Save saves, Escape
               or Cancel keeps it; leaving a field saves nothing, there are two of them, the number
               is checked when it is left (UX-6) -->
          <div
            class="flex-grow-1 min-w-0 px-3 py-2 d-flex flex-wrap align-items-start gap-2"
            role="group"
            [attr.aria-label]="'grocery.quantity.of' | t: { name: item.name }"
            (keydown.escape)="$event.preventDefault(); cancelQuantity(item)"
          >
            <span class="item-name text-break align-self-center me-auto">{{ item.name }}</span>
            <div class="quantity-amount">
              <input
                #amountField
                [id]="'amount-' + item.id"
                class="form-control"
                inputmode="decimal"
                autocomplete="off"
                [class.is-invalid]="quantityInvalid()"
                [attr.aria-label]="'grocery.quantity.amount' | t"
                [attr.aria-describedby]="quantityInvalid() ? 'amount-error-' + item.id : null"
                [value]="amountText(item.amount)"
                (input)="quantityInvalid.set(false)"
                (blur)="checkAmount(amountField)"
                (keydown.enter)="
                  $event.preventDefault(); saveQuantity(item, amountField, unitField)
                "
              />
              @if (quantityInvalid()) {
                <div class="invalid-feedback d-block" [id]="'amount-error-' + item.id">
                  {{ 'grocery.quantity.invalid' | t }}
                </div>
              }
            </div>
            <select
              #unitField
              [id]="'unit-' + item.id"
              class="form-select quantity-unit"
              [attr.aria-label]="'grocery.quantity.unit' | t"
              (keydown.enter)="$event.preventDefault(); saveQuantity(item, amountField, unitField)"
            >
              <option value="" [selected]="item.unit == null">
                {{ 'grocery.quantity.no_unit' | t }}
              </option>
              @for (unit of quantityUnits(); track unit.value) {
                <option [value]="unit.value" [selected]="unit.value === item.unit">
                  {{ unit.label }}
                </option>
              }
            </select>
            <button
              type="button"
              class="btn btn-primary"
              (click)="saveQuantity(item, amountField, unitField)"
            >
              {{ 'shared.save' | t }}
            </button>
            <button type="button" class="btn btn-outline-secondary" (click)="cancelQuantity(item)">
              {{ 'shared.cancel' | t }}
            </button>
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
              @if (item.amount != null) {
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
            [disabled]="quantityOf() === item.id"
            [title]="quantityOf() === item.id ? ('grocery.quantity.editing' | t) : ''"
            (click)="startQuantity(item)"
          >
            <i class="fa-solid fa-scale-balanced me-2" aria-hidden="true"></i
            >{{ 'grocery.quantity.label' | t }}
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
              [attr.aria-checked]="item.categoryId == null"
              [disabled]="categoryBusy() === item.id"
              (click)="setCategory(item, null)"
            >
              <span class="d-inline-block me-2 category-icon" aria-hidden="true"></span
              >{{ 'grocery.list.no_category' | t }}
              @if (item.categoryId == null) {
                <i class="fa-solid fa-check ms-2" aria-hidden="true"></i>
              }
            </button>
          </div>
        </app-action-menu>
        <!-- the error of what was done to the item, in its row (UX-15) -->
        @if (itemError.of(item.id); as error) {
          <div class="w-100 px-3 pb-2">
            <p class="alert alert-danger mb-0" role="alert">{{ error }}</p>
          </div>
        }
      </li>
    </ng-template>

    <!-- the photo the robot reads the items in; no capture either (GROCERY-16) -->
    <input
      #discoverInput
      type="file"
      accept="image/*"
      hidden
      data-testid="discover-input"
      (change)="discoverChosen($any($event.target))"
    />

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
  // where an error shows (UX-15): the lists, or the items with the categories could not be loaded,
  // at the top; adding an item or reading a photo under its field; adding the items read in their
  // form; what was done to an item in its row, at the top once the item is not shown
  protected readonly listsError = signal<string | null>(null);
  protected readonly itemsError = signal<string | null>(null);
  protected readonly addError = signal<string | null>(null);
  protected readonly discoveredError = signal<string | null>(null);
  protected readonly itemError = new RowError<number>();
  protected strayItemError(): string | null {
    return this.itemError.stray((id) => this.items().some((item) => item.id === id));
  }

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
      icon: category.icon ?? null,
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
    this.service
      .settings()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (settings) => this.discoverItems.set(settings.discoverItems === true),
        error: () => this.discoverItems.set(false),
      });
    // the items and the categories of the list shown, anew when another one is chosen; what was done on the list
    // before goes to the backend
    effect(() => {
      const list = this.shown();
      untracked(() => {
        this.pending.commit();
        // the items read for another list go with it, they are never added to this one
        if (list?.id !== this.discoveredList) this.closeDiscoveredOf();
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
      this.itemAmount.set(chosen.amount == null ? '' : String(chosen.amount));
      this.itemUnit.set(chosen.unit ?? '');
      this.itemCategory.set(chosen.categoryId ?? null);
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
    this.addError.set(null);
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
          this.addError.set(apiErrorMessage(error, this.i18n.t('grocery.list.not_added')));
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
    this.itemError.clear();
    this.pending.start(
      { text: this.i18n.t('grocery.list.bought', { name: item.name }) },
      () => {
        this.struck.update((ids) => without(ids, item.id));
        this.closeFields(item.id);
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
          item.id,
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
    this.closeFields(item.id);
    this.itemError.clear();
    this.items.update((items) => items.filter((each) => each.id !== item.id));
    this.send(this.service.deleteItem(item.id), this.i18n.t('grocery.list.not_deleted'), item.id);
  }

  protected undo(): void {
    this.pending.undo();
  }

  // the item whose name is in a field, to fix it, to buy or bought; an empty name is not saved
  protected readonly renaming = signal<number | null>(null);
  protected readonly renameEmpty = signal(false);

  protected startRename(item: GroceryItem): void {
    // one field in a row at a time: the quantity is not saved
    this.quantityOf.set(null);
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
    this.closeFields(item.id);
    this.focusRow(item.id);
  }

  // the fields of the item closed, its name or its quantity, e.g. when the item leaves the list
  private closeFields(id: number): void {
    if (this.renaming() === id) {
      this.renameEmpty.set(false);
      this.renaming.set(null);
    }
    if (this.quantityOf() === id) {
      this.quantityInvalid.set(false);
      this.quantityOf.set(null);
    }
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
    this.itemError.clear();
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
          this.itemError.set(
            item.id,
            apiErrorMessage(error, this.i18n.t('grocery.list.not_renamed')),
          );
          this.loadItems(this.shown());
        },
      });
  }

  // the item whose quantity is in fields, to buy or bought, and the units to choose for it, the one it
  // has among them (GROCERY-15); an amount that is no positive number is not saved
  protected readonly quantityOf = signal<number | null>(null);
  protected readonly quantityInvalid = signal(false);
  protected readonly quantityUnits = signal<UnitOption[]>([]);
  protected readonly amountText = amountText;
  protected readonly parseAmount = parseAmount;

  protected startQuantity(item: GroceryItem): void {
    // one field in a row at a time: a name left open was saved when its field was left
    this.renaming.set(null);
    this.renameEmpty.set(false);
    this.quantityInvalid.set(false);
    this.quantityUnits.set(unitOptions(this.i18n, item.unit));
    this.quantityOf.set(item.id);
    afterNextRender(
      () => {
        const field = this.host.nativeElement.querySelector<HTMLInputElement>(`#amount-${item.id}`);
        field?.focus();
        field?.select();
      },
      { injector: this.injector },
    );
  }

  // the number is checked when its field is left (UX-6)
  protected checkAmount(field: HTMLInputElement): void {
    this.quantityInvalid.set(parseAmount(field.value) === undefined);
  }

  // Escape or Cancel keeps the quantity, the focus goes back to the row
  protected cancelQuantity(item: GroceryItem): void {
    this.closeFields(item.id);
    this.focusRow(item.id);
  }

  // the quantity once Enter or Save is pressed, with the name and the category the item has; no
  // number takes the unit away too, the backend keeps no unit without one. The focus goes back to
  // the row
  protected saveQuantity(
    item: GroceryItem,
    amountField: HTMLInputElement,
    unitField: HTMLSelectElement,
  ): void {
    if (this.quantityOf() !== item.id) return;
    const amount = parseAmount(amountField.value);
    if (amount === undefined) {
      this.quantityInvalid.set(true);
      return;
    }
    const unit = amount === null ? null : unitField.value || null;
    this.closeFields(item.id);
    this.focusRow(item.id);
    if (amount === (item.amount ?? null) && unit === (item.unit ?? null)) return;
    this.itemError.clear();
    this.service
      .updateItem(item.id, { name: item.name, amount, unit, categoryId: item.categoryId })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (changed) =>
          this.items.update((items) =>
            items.map((each) => (each.id === changed.id ? changed : each)),
          ),
        error: (error: unknown) => {
          this.itemError.set(
            item.id,
            apiErrorMessage(error, this.i18n.t('grocery.quantity.not_saved')),
          );
          this.loadItems(this.shown());
        },
      });
  }

  // whether the robot offers to read the items in a photo: the AI features turned on, asked once
  // with the page, not offered when that fails (GROCERY-16)
  protected readonly discoverItems = signal(false);
  // the photo is being sent and read
  protected readonly discovering = signal(false);
  // the items read in the photo, null while none are shown
  protected readonly discovered = signal<DiscoveredRow[] | null>(null);
  protected readonly addingDiscovered = signal(false);
  // the list the photo was read for, the only one its items are added to
  private discoveredList: number | null = null;
  // at least one item left in; one that is not valid says why once Add is pressed
  protected readonly canAddDiscovered = computed(() =>
    (this.discovered() ?? []).some((row) => row.include),
  );
  private readonly discoverInput =
    viewChild.required<ElementRef<HTMLInputElement>>('discoverInput');

  protected chooseDiscoverPhoto(): void {
    this.discoverInput().nativeElement.click();
  }

  // the photo scaled down and stored as the one of an item, the items the AI reads in it shown to
  // correct them; the photo stays in the file storage, attached to no item
  protected async discoverChosen(input: HTMLInputElement): Promise<void> {
    const file = input.files?.[0];
    input.value = '';
    const list = this.shown()?.id;
    if (!file || list == null) return;
    this.discovering.set(true);
    this.closeDiscoveredOf();
    this.addError.set(null);
    try {
      const scaled = await this.scaler.scale(file);
      const stored = await firstValueFrom(this.service.uploadPhoto(scaled));
      const items = await firstValueFrom(this.service.discover(stored.id));
      // read while another list was chosen: not shown there
      if (this.destroyed || this.shown()?.id !== list) return;
      this.discoveredList = list;
      this.discovered.set(items.map((each, key) => this.discoveredRow(key, each)));
      afterNextRender(
        () => this.host.nativeElement.querySelector<HTMLElement>('#discovered')?.focus(),
        {
          injector: this.injector,
        },
      );
    } catch (error: unknown) {
      this.addError.set(apiErrorMessage(error, this.i18n.t('grocery.discover.not_read')));
    } finally {
      this.discovering.set(false);
    }
  }

  // an item read, in with the quantity the AI read; a unit not among the usual ones stays one to
  // choose
  private discoveredRow(key: number, item: GroceryDiscoveredItem): DiscoveredRow {
    return {
      key,
      include: true,
      name: item.name,
      amount: amountText(item.amount),
      unit: item.unit ?? '',
      units: unitOptions(this.i18n, item.unit),
      nameEmpty: false,
      amountInvalid: false,
    };
  }

  protected changeDiscovered(key: number, change: Partial<DiscoveredRow>): void {
    this.discovered.update(
      (rows) => rows?.map((row) => (row.key === key ? { ...row, ...change } : row)) ?? null,
    );
  }

  private validDiscovered(row: DiscoveredRow): boolean {
    return !!row.name.trim() && parseAmount(row.amount) !== undefined;
  }

  // the items left in added one after the other, without a category: the backend gives each the
  // one it learned for its name (GROCERY-13). The ones the backend refuses stay to try again, an
  // item left in that is not valid says why and none is added
  protected async addDiscovered(): Promise<void> {
    const rows = (this.discovered() ?? []).filter((row) => row.include);
    const list = this.discoveredList;
    if (list == null || !rows.length || this.addingDiscovered()) return;
    if (!rows.every((row) => this.validDiscovered(row))) {
      for (const row of rows) {
        this.changeDiscovered(row.key, {
          nameEmpty: !row.name.trim(),
          amountInvalid: parseAmount(row.amount) === undefined,
        });
      }
      return;
    }
    this.addingDiscovered.set(true);
    this.discoveredError.set(null);
    const failed: DiscoveredRow[] = [];
    for (const row of rows) {
      const amount = parseAmount(row.amount) ?? null;
      try {
        await firstValueFrom(
          this.service.addItem(list, {
            name: row.name.trim(),
            amount,
            unit: amount === null ? null : row.unit || null,
          }),
        );
      } catch {
        failed.push(row);
      }
    }
    this.addingDiscovered.set(false);
    // another list was chosen meanwhile: its items are loaded with it
    if (this.destroyed || this.discoveredList !== list) return;
    if (failed.length) {
      this.discovered.set(failed);
      this.discoveredError.set(this.i18n.t('grocery.discover.not_all_added'));
    } else {
      this.closeDiscoveredOf();
    }
    if (failed.length < rows.length) this.loadItems(this.shown());
  }

  // closed, nothing is added; the focus goes back to the field of a new item
  protected closeDiscovered(): void {
    this.closeDiscoveredOf();
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>('#item-name')?.focus(),
      {
        injector: this.injector,
      },
    );
  }

  private closeDiscoveredOf(): void {
    this.discovered.set(null);
    this.discoveredError.set(null);
    this.discoveredList = null;
  }

  // the category of the item, one of the list or none; the backend learns it for the next item of
  // the name on the list (GROCERY-13)
  protected setCategory(item: GroceryItem, categoryId: number | null): void {
    if (item.categoryId === categoryId) return;
    this.categoryBusy.set(item.id);
    this.itemError.clear();
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
          this.itemError.set(
            item.id,
            apiErrorMessage(error, this.i18n.t('grocery.list.category_not_set')),
          );
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
    this.itemError.clear();
    try {
      const scaled = await this.scaler.scale(file);
      const stored = await firstValueFrom(this.service.uploadPhoto(scaled));
      const changed = await firstValueFrom(this.service.attachPhoto(item.id, stored.id));
      this.items.update((items) => items.map((each) => (each.id === changed.id ? changed : each)));
    } catch (error: unknown) {
      this.itemError.set(
        item.id,
        apiErrorMessage(error, this.i18n.t('grocery.photo.not_attached')),
      );
    } finally {
      this.photoBusy.set(null);
    }
  }

  protected removePhoto(item: GroceryItem): void {
    this.photoBusy.set(item.id);
    this.itemError.clear();
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
          this.itemError.set(
            item.id,
            apiErrorMessage(error, this.i18n.t('grocery.photo.not_removed')),
          );
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
      this.itemError.set(item.id, apiErrorMessage(error, this.i18n.t('grocery.photo.not_loaded')));
    }
  }

  protected closePhoto(): void {
    const url = this.viewing()?.url;
    if (url) URL.revokeObjectURL(url);
    this.viewing.set(null);
  }

  // not ended with the page: what was done is sent also when the page is left; a failure says why
  // in the row of the item and brings the list back as the backend has it
  private send(
    request: Observable<unknown>,
    failure: string,
    item: number,
    done?: () => void,
  ): void {
    request.subscribe({
      next: () => {
        if (!this.destroyed) done?.();
      },
      error: (error: unknown) => {
        if (this.destroyed) return;
        this.itemError.set(item, apiErrorMessage(error, failure));
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
          this.listsError.set(null);
          this.lists.set(lists);
          if (!lists.some((list) => String(list.id) === this.id())) {
            void this.router.navigate(['/grocery'], { replaceUrl: true });
          }
        },
        error: (error: unknown) =>
          this.listsError.set(
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
    // the error of the categories, shown once the items are there
    let categoriesFailed: string | null = null;
    const categories = this.service.categories(list.id).pipe(
      catchError((error: unknown) => {
        categoriesFailed = apiErrorMessage(error, this.i18n.t('grocery.categories.not_loaded'));
        return of([]);
      }),
    );
    this.loadingItems = forkJoin([this.service.items(list.id, this.showBought()), categories])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ([items, categories]) => {
          // a field of an item that is gone closes (GROCERY-14)
          for (const open of [this.renaming(), this.quantityOf()]) {
            if (open !== null && !items.some((each) => each.id === open)) this.closeFields(open);
          }
          this.items.set(items);
          this.categories.set(categories);
          this.loaded.set(true);
          this.itemsError.set(categoriesFailed);
        },
        error: (error: unknown) =>
          this.itemsError.set(apiErrorMessage(error, this.i18n.t('grocery.list.items_not_loaded'))),
      });
  }
}
