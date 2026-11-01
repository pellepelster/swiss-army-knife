import {
  Component,
  computed,
  CUSTOM_ELEMENTS_SCHEMA,
  DestroyRef,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { apiErrorMessage } from '../shared/api';
import { GroceryList, GroceryService } from './grocery.service';
import { ListIcon } from './list-icon';

// creates a grocery list, or changes the settings of one of the user (its name, whether it is
// shared, its icon) and deletes it, on a page of its own. Afterwards the list shows. The icon is one
// emoji, chosen in the picker of emoji-picker-element, loaded when it is opened; its data comes
// from the frontend itself (/emoji/data.json), not from a cdn
@Component({
  selector: 'app-grocery-list-form',
  imports: [ListIcon, RouterLink],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        [routerLink]="back()"
        aria-label="Back"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ id() ? 'Settings' : 'Create list' }}</h1>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (!id() || list()) {
      <form class="vstack gap-3 card card-body" (submit)="$event.preventDefault(); save()">
        <div>
          <label class="form-label" for="list-name">Name</label>
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
          <span class="form-label d-block" id="list-icon-label">Icon</span>
          <div class="d-flex flex-wrap align-items-center gap-2">
            <app-list-icon [name]="name()" [icon]="icon()" [size]="48" data-testid="list-icon" />
            <div class="btn-group" role="group" aria-labelledby="list-icon-label">
              <button
                type="button"
                class="btn btn-outline-secondary"
                [attr.aria-expanded]="picking()"
                (click)="togglePicker()"
              >
                Choose icon
              </button>
              <button
                type="button"
                class="btn btn-outline-secondary"
                [disabled]="!icon()"
                (click)="icon.set(null)"
              >
                No icon
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
          <label class="form-check-label" for="list-shared">Shared</label>
          <div class="form-text">Every user sees a shared list and adds and deletes its items.</div>
        </div>

        <!-- one group of the actions; a delete asks inline in its place, Keep is the safe default -->
        <div class="d-flex flex-wrap align-items-center justify-content-end gap-2">
          @if (confirming()) {
            <span class="small">Delete the list and its items?</span>
            <div class="btn-group" role="group" aria-label="Delete the list">
              <button type="button" class="btn btn-danger" (click)="delete()">Delete</button>
              <button
                type="button"
                class="btn btn-outline-secondary"
                (click)="confirming.set(false)"
              >
                Keep
              </button>
            </div>
          } @else {
            <div class="btn-group" role="group" aria-label="List actions">
              @if (list()) {
                <button
                  type="button"
                  class="btn btn-outline-danger"
                  [disabled]="last()"
                  (click)="confirming.set(true)"
                >
                  Delete list
                </button>
              }
              <a class="btn btn-outline-secondary" [routerLink]="back()">Cancel</a>
              <button class="btn btn-primary" type="submit" [disabled]="!name().trim() || saving()">
                @if (saving()) {
                  <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
                } @else if (!id()) {
                  <i class="sak-icon-plus" aria-hidden="true"></i>
                }
                {{ id() ? 'Save' : 'Create list' }}
              </button>
            </div>
          }
        </div>
        @if (last()) {
          <div class="form-text text-end" data-testid="last-list">
            Your only list can not be deleted.
          </div>
        }
      </form>
    }
  `,
})
export class GroceryListForm implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(GroceryService);
  private readonly router = inject(Router);

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

  // where the settings were opened: the list edited, or all lists for a new one and from there
  protected readonly back = computed(() => {
    const id = this.id();
    return id && this.from() !== 'lists' ? ['/grocery/lists', id] : ['/grocery'];
  });

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
            this.error.set('There is no such list of yours.');
            return;
          }
          this.list.set(list);
          this.last.set(lists.filter((each) => each.mine).length === 1);
          this.name.set(list.name);
          this.shared.set(list.shared);
          this.icon.set(list.icon);
        },
        error: (error: unknown) =>
          this.error.set(apiErrorMessage(error, 'The list could not be loaded.')),
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
        this.error.set(apiErrorMessage(error, 'The list could not be saved.'));
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
          this.error.set(apiErrorMessage(error, 'The list could not be deleted.')),
      });
  }
}
