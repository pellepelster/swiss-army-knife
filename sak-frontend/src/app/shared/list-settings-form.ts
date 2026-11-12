import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  computed,
  CUSTOM_ELEMENTS_SCHEMA,
  DestroyRef,
  inject,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from './api';
import { ConfirmDelete } from './confirm-delete';
import { LIST_APP } from './list-app';
import { ListIcon } from './list-icon';
import { SharedList } from './list-service';
import { PageHeader } from './page-header';

// creates a list of a list-based app (LIST_APP), or changes the settings of a list the user sees
// (its name, whether it is shared, its icon) and deletes it, on a page of its own; on a shared list
// of another user every user changes the icon, only the owner renames, shares, unshares and
// deletes it (LIST-2). Afterwards the list shows. The icon is one emoji, chosen in the picker of
// emoji-picker-element, loaded when it is opened; its data comes from the frontend itself
// (/emoji/data.json), not from a cdn. Settings of an app of its own, e.g. the categories of a
// grocery list, come as content below the form
@Component({
  selector: 'app-list-settings-form',
  imports: [PageHeader, ConfirmDelete, ListIcon, RouterLink, TranslatePipe],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader [back]="back()" [backLabel]="texts.back | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">
        {{ (id() ? 'shared.settings' : texts.create) | t }}
      </h1>
    </header>

    @if (loadError(); as error) {
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
            [disabled]="!owner()"
            [title]="owner() ? '' : (texts.onlyOwnerShares | t)"
            [value]="name()"
            (input)="name.set(nameField.value)"
          />
        </div>
        <div>
          <span class="form-label d-block" id="list-icon-label">{{ texts.icon | t }}</span>
          <div class="d-flex flex-wrap align-items-center gap-2">
            <app-list-icon [name]="name()" [icon]="icon()" [size]="48" data-testid="list-icon" />
            <div class="btn-group" role="group" aria-labelledby="list-icon-label">
              <button
                type="button"
                class="btn btn-outline-secondary"
                [attr.aria-expanded]="picking()"
                (click)="togglePicker()"
              >
                {{ texts.chooseIcon | t }}
              </button>
              <button
                type="button"
                class="btn btn-outline-secondary"
                [disabled]="!icon()"
                (click)="icon.set(null)"
              >
                {{ texts.noIcon | t }}
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
            [disabled]="!owner()"
            [title]="owner() ? '' : (texts.onlyOwnerShares | t)"
            (change)="shared.set(sharedInput.checked)"
          />
          <label class="form-check-label" for="list-shared">{{ texts.shared | t }}</label>
          <div class="form-text" data-testid="shared-hint">
            @if (owner()) {
              {{ texts.sharedHint | t }}
            } @else {
              {{ texts.onlyOwnerShares | t }} {{ texts.onlyOwnerDeletes | t }}
            }
          </div>
        </div>

        <!-- the error of a save or a delete, above their buttons (UX-15) -->
        @if (error(); as error) {
          <p class="alert alert-danger mb-0" role="alert" data-testid="form-error">{{ error }}</p>
        }

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
                  [disabled]="!owner()"
                  [title]="owner() ? '' : (texts.onlyOwnerDeletes | t)"
                  (click)="confirming.set(true)"
                >
                  {{ texts.deleteList | t }}
                </button>
              }
              <a class="btn btn-outline-secondary" [routerLink]="back()">{{
                'shared.cancel' | t
              }}</a>
              <!-- save in the settings in the outline of the accent, create stays filled -->
              <button
                class="btn"
                [class.btn-primary]="!id()"
                [class.btn-outline-secondary]="!!id()"
                [class.btn-field-primary]="!!id()"
                type="submit"
                [disabled]="!name().trim() || saving()"
              >
                @if (saving()) {
                  <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
                } @else if (!id()) {
                  <i class="sak-icon-plus" aria-hidden="true"></i>
                }
                {{ (id() ? 'shared.save' : texts.create) | t }}
              </button>
            </div>
          }
        </div>
      </form>
    }

    <ng-content />
  `,
})
export class ListSettingsForm implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly app = inject(LIST_APP);
  private readonly service = inject(this.app.service);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  // the route parameter, missing for a new list
  readonly id = input<string | undefined>();
  // the query parameter: lists when the settings were opened from all lists, they lead back there
  readonly from = input<string | undefined>();

  protected readonly texts = this.app.texts;
  protected readonly list = signal<SharedList | null>(null);
  protected readonly name = signal('');
  protected readonly shared = signal(false);
  // one emoji, null for none
  protected readonly icon = signal<string | null>(null);
  protected readonly picking = signal(false);
  protected readonly saving = signal(false);
  protected readonly confirming = signal(false);
  // the list of the settings once it is loaded
  readonly loaded = output<SharedList>();
  // the user owns the list, or creates one: only then they share, unshare and delete it (LIST-2)
  protected readonly owner = computed(() => this.list()?.mine ?? true);
  // the list could not be loaded; a save or a delete failed (UX-15)
  protected readonly loadError = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  // where the settings were opened: the list edited, or all lists for a new one and from there
  protected readonly back = computed(() => {
    const id = this.id();
    return id && this.from() !== 'lists' ? [`${this.app.route}/lists`, id] : [this.app.route];
  });

  ngOnInit(): void {
    const id = this.id();
    if (!id) return;
    this.service
      .lists()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (lists) => {
          const list = lists.find((each) => String(each.id) === id);
          if (!list) {
            this.loadError.set(this.i18n.t(this.texts.noSuchList));
            return;
          }
          this.list.set(list);
          this.name.set(list.name);
          this.shared.set(list.shared);
          this.icon.set(list.icon ?? null);
          this.loaded.emit(list);
        },
        error: (error: unknown) =>
          this.loadError.set(apiErrorMessage(error, this.i18n.t(this.texts.notLoaded))),
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
        void this.router.navigate(
          id === undefined ? [`${this.app.route}/lists`, list.id] : this.back(),
        ),
      error: (error: unknown) => {
        this.saving.set(false);
        // the owner unshared or deleted it while the settings were open
        this.error.set(
          error instanceof HttpErrorResponse && error.status === 404
            ? this.i18n.t(this.texts.listGone)
            : apiErrorMessage(error, this.i18n.t(this.texts.notSaved)),
        );
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
    this.error.set(null);
    this.service
      .deleteList(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigate([this.app.route]),
        error: (error: unknown) =>
          this.error.set(apiErrorMessage(error, this.i18n.t(this.texts.notDeleted))),
      });
  }
}
