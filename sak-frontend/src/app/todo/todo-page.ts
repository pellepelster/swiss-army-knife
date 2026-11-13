import { DOCUMENT } from '@angular/common';
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
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { filter, fromEvent, Observable, Subscription } from 'rxjs';
import { I18n, TextKey, TranslatePipe } from '../i18n/i18n';
import { apiErrorMessage } from '../shared/api';
import { ActionMenu } from '../shared/action-menu';
import { Keepalive } from '../shared/keepalive';
import { ListIcon } from '../shared/list-icon';
import { OpenList } from '../shared/open-list';
import { PageHeader } from '../shared/page-header';
import { PendingUndo } from '../shared/pending-undo';
import { RowError } from '../shared/row-error';
import { UNDO_DELETE_MS, UndoToast } from '../shared/undo-toast';
import { PriorityIcon } from './priority-icon';
import { TodoItem, TodoList, TodoPriority, TodoService } from './todo.service';

// the day as the user's browser writes it, e.g. 24.12.2026; a day has no time zone
export function dayText(day: string): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' }).format(
    new Date(`${day}T00:00:00Z`),
  );
}

// today in the time zone of the browser, yyyy-mm-dd as the backend writes a day
function today(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

// the text of a priority, e.g. High priority
const PRIORITY_LABEL = {
  high: 'todo.priority.high',
  normal: 'todo.priority.normal',
  low: 'todo.priority.low',
} as const satisfies Record<TodoPriority, TextKey>;

// one todo list, opened from all lists (LIST-3): its tasks by priority (TODO-5), with their due
// date (TODO-4); a tap marks a task done or open again (TODO-3); purge removes the done tasks after
// an undo time (TODO-6, UX-4.2). A list that is gone leads to all lists
@Component({
  selector: 'app-todo-page',
  imports: [ActionMenu, PageHeader, ListIcon, PriorityIcon, RouterLink, TranslatePipe, UndoToast],
  host: {
    class: 'd-block container pb-5',
    // the menu of the priority of the next task closes on a click elsewhere and on Escape
    '(document:click)': 'choosingPriority.set(false)',
    '(document:keydown.escape)': 'choosingPriority.set(false)',
  },
  template: `
    <header appPageHeader back="/todo" [backLabel]="'todo.all_lists' | t">
      @if (shown(); as list) {
        <app-list-icon [name]="list.name" [icon]="list.icon" />
      }
      <h1 class="h3 mb-0 flex-grow-1 text-break" data-testid="list-name">
        {{ shown()?.name }}
        @if (shown()?.shared) {
          <span class="badge border text-body-secondary fw-normal fs-6 align-middle">{{
            'todo.shared_badge' | t
          }}</span>
        }
      </h1>
      <!-- one toolbar (UX-10): the purge of the done tasks, red, it deletes them (UX-3), disabled
           while there are none (UX-8); every user who sees a list purges it and changes its settings
           (LIST-2) -->
      @if (shown(); as list) {
        <div class="btn-group" role="group" [attr.aria-label]="'shared.actions' | t">
          <button
            type="button"
            class="btn btn-outline-danger"
            data-testid="purge"
            [disabled]="!hasDone()"
            [title]="(hasDone() ? 'todo.list.purge' : 'todo.list.nothing_to_purge') | t"
            (click)="purge()"
          >
            <i class="fa-solid fa-broom" aria-hidden="true"></i> {{ 'todo.list.purge' | t }}
          </button>
          <a class="btn btn-outline-secondary" [routerLink]="['/todo/lists', list.id, 'edit']"
            ><i class="fa-solid fa-gear" aria-hidden="true"></i> {{ 'shared.settings' | t }}</a
          >
        </div>
      }
    </header>

    @if (listsError() ?? itemsError() ?? purgeError() ?? strayItemError(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (shown()) {
      <form class="card card-body mb-4" (submit)="$event.preventDefault(); add()">
        <label class="form-label" for="item-title">{{ 'todo.list.task' | t }}</label>
        <div class="input-group">
          <input
            #title
            id="item-title"
            class="form-control"
            autocomplete="off"
            maxlength="256"
            [value]="itemTitle()"
            (input)="itemTitle.set(title.value)"
          />
          <!-- the due date (TODO-4): a calendar button opens the picker of the hidden field next
               to it, so the row fits a phone -->
          <button
            type="button"
            class="btn btn-outline-secondary"
            data-testid="item-due-button"
            [class.text-nowrap]="!!itemDue()"
            [attr.aria-label]="dueLabel()"
            [title]="dueLabel()"
            (click)="openDuePicker(due)"
          >
            <i class="fa-solid fa-calendar" aria-hidden="true"></i>
            @if (itemDue(); as day) {
              <span class="ms-1 small">{{ shortDayText(day) }}</span>
            }
          </button>
          <input
            #due
            id="item-due"
            type="date"
            class="visually-hidden"
            tabindex="-1"
            [attr.aria-label]="'todo.list.due_date' | t"
            [value]="itemDue()"
            (input)="itemDue.set(due.value)"
          />
          <!-- the priority of the next task (TODO-5): a flag in the color of the priority -->
          <button
            type="button"
            class="btn btn-outline-secondary"
            data-testid="item-priority"
            aria-haspopup="menu"
            [attr.aria-expanded]="choosingPriority()"
            [attr.aria-label]="priorityText()"
            [title]="priorityText()"
            (click)="$event.stopPropagation(); choosingPriority.set(!choosingPriority())"
          >
            <i
              class="fa-solid fa-flag"
              [class.text-danger]="itemPriority() === 'high'"
              [class.text-body-secondary]="itemPriority() === 'low'"
              aria-hidden="true"
            ></i>
          </button>
          <div
            class="dropdown-menu end-0 top-100 mt-1"
            [class.show]="choosingPriority()"
            role="menu"
            [attr.aria-label]="'todo.priority.label' | t"
          >
            @for (each of priorities; track each) {
              <button
                type="button"
                class="dropdown-item"
                role="menuitemradio"
                [attr.aria-checked]="itemPriority() === each"
                [attr.data-testid]="'item-priority-' + each"
                (click)="itemPriority.set(each); choosingPriority.set(false)"
              >
                <i
                  class="fa-solid fa-fw me-1"
                  [class.fa-check]="itemPriority() === each"
                  aria-hidden="true"
                ></i>
                <app-priority-icon class="me-2" [priority]="each" />{{ priorityLabel[each] | t }}
              </button>
            }
          </div>
          <!-- only the icon of what it adds, its label and tooltip say what it does (UX-2) -->
          <button
            class="btn btn-primary"
            type="submit"
            [attr.aria-label]="'todo.list.add_task' | t"
            [title]="'todo.list.add_task' | t"
            [disabled]="!itemTitle().trim() || adding()"
          >
            @if (adding()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            } @else {
              <i class="sak-icon-plus" aria-hidden="true"></i>
            }
          </button>
        </div>
        <!-- the error of adding the task, under the field (UX-15) -->
        @if (addError(); as error) {
          <p class="alert alert-danger mt-2 mb-0" role="alert">{{ error }}</p>
        }
      </form>

      @if (items().length) {
        <ul class="list-group" [attr.aria-label]="'todo.list.tasks' | t">
          @for (item of items(); track item.id) {
            <!-- the row is a toggle, no checkbox (TODO-3): pressed while done; its name says the
                 title, the priority and the due date, its content is only visual -->
            <li
              class="list-group-item p-0 d-flex flex-wrap align-items-center"
              [attr.data-testid]="'item-' + item.id"
            >
              <button
                type="button"
                class="btn todo-item flex-grow-1 min-w-0 rounded-0 border-0 text-start d-flex align-items-center gap-3 px-3 py-2"
                [attr.aria-label]="toggleLabel(item)"
                [attr.aria-pressed]="!!item.doneAt"
                (click)="done(item)"
              >
                <!-- an arrow up for a high priority, a dash for a normal one, an arrow down for a low
                     one, so every row has one and the titles line up (TODO-5) -->
                <app-priority-icon
                  [priority]="item.priority"
                  [title]="priorityLabel[item.priority] | t"
                  [attr.data-testid]="'priority-' + item.id"
                />
                <span
                  class="flex-grow-1 text-break"
                  [class.item-struck]="!!item.doneAt"
                  [class.text-body-secondary]="!!item.doneAt"
                  >{{ item.title }}</span
                >
                @if (item.dueDate) {
                  <span
                    class="small text-nowrap"
                    [class.text-danger]="item.dueDate < today()"
                    [class.text-body-secondary]="item.dueDate >= today()"
                    [attr.data-testid]="'due-' + item.id"
                    >{{ dueText(item.dueDate) }}</span
                  >
                }
              </button>
              <!-- the priority (TODO-5) and the due date (TODO-4); an entry that does not apply is
                   disabled (UX-8) -->
              <app-action-menu class="pe-1" [name]="item.title">
                @for (each of priorities; track each) {
                  <button
                    type="button"
                    class="dropdown-item"
                    role="menuitemradio"
                    [attr.aria-checked]="item.priority === each"
                    [attr.data-testid]="'set-priority-' + each"
                    (click)="changePriority(item, each)"
                  >
                    <i
                      class="fa-solid fa-fw me-1"
                      [class.fa-check]="item.priority === each"
                      aria-hidden="true"
                    ></i>
                    <app-priority-icon class="me-2" [priority]="each" />{{
                      priorityLabel[each] | t
                    }}
                  </button>
                }
                <div class="dropdown-divider" role="separator"></div>
                <button
                  type="button"
                  class="dropdown-item"
                  role="menuitem"
                  data-testid="add-due-date"
                  [disabled]="!!item.dueDate"
                  [title]="item.dueDate ? ('todo.list.has_due_date' | t) : ''"
                  (click)="startDueDate(item)"
                >
                  <i class="fa-solid fa-fw fa-calendar-plus me-2" aria-hidden="true"></i
                  >{{ 'todo.list.add_due_date' | t }}
                </button>
                <button
                  type="button"
                  class="dropdown-item"
                  role="menuitem"
                  data-testid="remove-due-date"
                  [disabled]="!item.dueDate"
                  [title]="item.dueDate ? '' : ('todo.list.no_due_date_yet' | t)"
                  (click)="removeDueDate(item)"
                >
                  <i class="fa-solid fa-fw fa-calendar-xmark me-2" aria-hidden="true"></i
                  >{{ 'todo.list.remove_due_date' | t }}
                </button>
              </app-action-menu>
              <!-- the due date added in the row: Enter or Save saves, Escape or Cancel keeps the task
                   without one, each leads back to the menu of the row; Save waits for a day (UX-6) -->
              @if (dating() === item.id) {
                <form
                  class="d-flex flex-wrap align-items-center gap-2 px-3 pb-2 w-100"
                  (submit)="$event.preventDefault(); saveDueDate(item, dueField.value)"
                  (keydown.escape)="$event.preventDefault(); closeDueDate(item.id)"
                >
                  <input
                    #dueField
                    type="date"
                    class="form-control w-auto"
                    [id]="'due-date-' + item.id"
                    [attr.aria-label]="'todo.list.due_date' | t"
                    (input)="dueTyped.set(dueField.value)"
                  />
                  <button class="btn btn-primary" type="submit" [disabled]="!dueTyped()">
                    {{ 'shared.save' | t }}
                  </button>
                  <button
                    type="button"
                    class="btn btn-outline-secondary"
                    (click)="closeDueDate(item.id)"
                  >
                    {{ 'shared.cancel' | t }}
                  </button>
                </form>
              }
              <!-- the error of marking it done, of its priority or its due date, in its row (UX-15) -->
              @if (itemError.of(item.id); as error) {
                <p class="alert alert-danger m-2 w-100" role="alert">{{ error }}</p>
              }
            </li>
          }
        </ul>
      } @else if (loaded()) {
        <p class="text-center text-body-secondary fs-4 py-5">{{ 'todo.list.empty' | t }}</p>
      }
    }

    @for (each of undoable(); track each) {
      <app-undo-toast [text]="each.text" (undo)="undo()" />
    }
  `,
})
export class TodoPage {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(TodoService);
  private readonly keepalive = inject(Keepalive);
  private readonly i18n = inject(I18n);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly injector = inject(Injector);

  // the route parameter, the id of the list shown
  readonly id = input.required<string>();

  // the list shown among the lists, loaded again with its tasks on a change of it (LIST-4)
  private readonly open = new OpenList<TodoList>(this.id, () => this.loadItems());
  protected readonly shown = this.open.shown;

  // the tasks as the backend answered them
  private readonly loadedItems = signal<TodoItem[]>([]);
  // the done tasks purged, hidden while it can be undone and until the backend answered the purge,
  // so a load meanwhile does not bring them back (TODO-6)
  private readonly hidden = signal<ReadonlySet<number>>(new Set());
  // the tasks marked done (the time) or open again (null) whose request is on its way: they show so
  // whatever a load meanwhile answers, and a tap on them waits for it (TODO-3)
  private readonly marking = signal<ReadonlyMap<number, string | null>>(new Map());
  protected readonly items = computed(() => {
    const hidden = this.hidden();
    const marking = this.marking();
    return this.loadedItems()
      .filter((item) => !hidden.has(item.id))
      .map((item) =>
        marking.has(item.id) ? { ...item, doneAt: marking.get(item.id) ?? null } : item,
      );
  });
  protected readonly hasDone = computed(() => this.items().some((item) => item.doneAt != null));
  protected readonly loaded = signal(false);
  // where an error shows (UX-15): loading at the top, adding under the field, a change of a task
  // in its row, or at the top once the task is not shown
  protected readonly listsError = this.open.error;
  protected readonly itemsError = signal<string | null>(null);
  protected readonly addError = signal<string | null>(null);
  // a purge refused, at the top until the next one or until one goes through (TODO-6)
  protected readonly purgeError = signal<string | null>(null);
  protected readonly itemError = new RowError<number>();
  protected strayItemError(): string | null {
    return this.itemError.stray((id) => this.items().some((item) => item.id === id));
  }

  protected readonly itemTitle = signal('');
  // yyyy-mm-dd as the date field gives it, empty for no day
  protected readonly itemDue = signal('');
  protected readonly itemPriority = signal<TodoPriority>('normal');
  protected readonly choosingPriority = signal(false);
  // the name of the flag, the priority chosen
  protected readonly priorityText = computed(
    () =>
      `${this.i18n.t('todo.priority.label')}: ${this.i18n.t(PRIORITY_LABEL[this.itemPriority()])}`,
  );
  // the name of the calendar, with the day chosen
  protected readonly dueLabel = computed(() => {
    const day = this.itemDue();
    return day
      ? `${this.i18n.t('todo.list.due_date')}: ${dayText(day)}`
      : this.i18n.t('todo.list.due_date');
  });

  // the picker of the browser on the hidden field; a browser without one gets the field focused
  protected openDuePicker(field: HTMLInputElement): void {
    try {
      field.showPicker();
    } catch {
      field.focus();
      field.click();
    }
  }

  // a day as short as it is clear, e.g. Dec 24, for the calendar of the next task
  protected shortDayText(day: string): string {
    return new Intl.DateTimeFormat(undefined, {
      month: 'short',
      day: 'numeric',
      timeZone: 'UTC',
    }).format(new Date(`${day}T00:00:00Z`));
  }
  protected readonly priorities: readonly TodoPriority[] = ['high', 'normal', 'low'];
  protected readonly priorityLabel = PRIORITY_LABEL;
  // what a due date is compared with, anew with every load and when the page shows again, so a
  // task becomes overdue after midnight
  protected readonly today = signal(today());
  protected readonly adding = signal(false);

  // the purge while it can be undone: what the toast says
  private readonly pending = new PendingUndo<{ text: string }>(UNDO_DELETE_MS);
  protected readonly undoable = this.pending.undoable;

  // the page is gone: what is sent then loads nothing again
  private destroyed = false;
  private loadingItems?: Subscription;

  constructor() {
    // the tasks of the list shown, anew when another one is chosen, not when the lists load again;
    // what was done on the list before goes to the backend
    effect(() => {
      this.open.shownId();
      untracked(() => {
        this.pending.commit();
        this.loadedItems.set([]);
        this.loaded.set(false);
        this.itemsError.set(null);
        this.purgeError.set(null);
        this.dating.set(null);
        this.loadItems();
      });
    });
    this.destroyRef.onDestroy(() => (this.destroyed = true));
    // the purge goes to the backend also when the page or the tab goes away or into the background
    this.pending.commitWhenLeaving(this.destroyRef);
    const document = inject(DOCUMENT);
    fromEvent(document, 'visibilitychange')
      .pipe(
        filter(() => document.visibilityState === 'visible'),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.today.set(today()));
  }

  protected add(): void {
    const title = this.itemTitle().trim();
    const list = this.open.shownId();
    if (!title || list === null) return;
    this.adding.set(true);
    this.addError.set(null);
    this.service
      .addItem(list, title, this.itemDue() || null, this.itemPriority())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.adding.set(false);
          this.itemTitle.set('');
          this.itemDue.set('');
          this.itemPriority.set('normal');
          this.loadItems();
        },
        error: (error: unknown) => {
          this.adding.set(false);
          this.addError.set(apiErrorMessage(error, this.i18n.t('todo.list.not_added')));
        },
      });
  }

  // shows at once (TODO-3); a tap while the request is on its way does nothing. The place of the
  // task follows with the next load
  protected done(item: TodoItem): void {
    if (this.marking().has(item.id)) return;
    const done = item.doneAt == null;
    this.marking.update((marking) =>
      new Map(marking).set(item.id, done ? new Date().toISOString() : null),
    );
    this.send(this.service.setDone(item.id, done), 'todo.list.not_done', item.id);
  }

  protected changePriority(item: TodoItem, priority: TodoPriority): void {
    if (item.priority === priority) return;
    this.send(this.service.setPriority(item.id, priority), 'todo.list.priority_not_set', item.id);
  }

  // the task whose due date is added in its row, and the day typed there (TODO-4)
  protected readonly dating = signal<number | null>(null);
  protected readonly dueTyped = signal('');

  // the field of the due date in the row of a task without one, focused
  protected startDueDate(item: TodoItem): void {
    this.dueTyped.set('');
    this.dating.set(item.id);
    afterNextRender(
      () =>
        this.host.nativeElement.querySelector<HTMLInputElement>(`#due-date-${item.id}`)?.focus(),
      { injector: this.injector },
    );
  }

  // the day typed becomes the due date of the task; nothing without one
  protected saveDueDate(item: TodoItem, day: string): void {
    if (!day) return;
    this.closeDueDate(item.id);
    this.send(this.service.setDueDate(item.id, day), 'todo.list.due_date_not_saved', item.id);
  }

  // the field of the due date goes, the focus back to the menu of the row it was opened from
  protected closeDueDate(id: number): void {
    this.dating.set(null);
    afterNextRender(
      () =>
        this.host.nativeElement
          .querySelector<HTMLButtonElement>(
            `[data-testid="item-${id}"] app-action-menu button[aria-haspopup]`,
          )
          ?.focus(),
      { injector: this.injector },
    );
  }

  protected removeDueDate(item: TodoItem): void {
    this.send(this.service.setDueDate(item.id, null), 'todo.list.due_date_not_saved', item.id);
  }

  // hides the done tasks shown; the backend deletes only these after UNDO_DELETE_MS unless undone
  // (TODO-6, UX-4.2), so a task done meanwhile stays
  protected purge(): void {
    const list = this.open.shownId();
    const ids = this.items()
      .filter((item) => item.doneAt != null)
      .map((item) => item.id);
    if (list === null || !ids.length) return;
    this.itemError.clear();
    this.purgeError.set(null);
    this.pending.start(
      { text: this.i18n.t('todo.list.purged') },
      (leaving) => this.sendPurge(list, ids, leaving),
      () => this.show(ids),
    );
    this.hidden.update((hidden) => new Set([...hidden, ...ids]));
  }

  protected undo(): void {
    this.pending.undo();
  }

  // the name of the toggle of a task: what it does, the priority unless normal, the due date; the
  // state is aria-pressed
  protected toggleLabel(item: TodoItem): string {
    const parts = [this.i18n.t('todo.list.done_toggle', { title: item.title })];
    if (item.priority !== 'normal') parts.push(this.i18n.t(PRIORITY_LABEL[item.priority]));
    if (item.dueDate) parts.push(this.dueText(item.dueDate));
    return parts.join(', ');
  }

  // due 24.12.2026, or overdue since then once the day passed (TODO-4)
  protected dueText(day: string): string {
    return this.i18n.t(day < this.today() ? 'todo.list.overdue' : 'todo.list.due', {
      date: dayText(day),
    });
  }

  // the purged tasks stay hidden until the backend answers, then the tasks load again
  private sendPurge(list: number, ids: readonly number[], leaving: boolean): void {
    const settled = (purged: boolean) => {
      if (this.destroyed) return;
      if (purged) {
        this.purgeError.set(null);
        this.loadedItems.update((items) => items.filter((item) => !ids.includes(item.id)));
      }
      this.show(ids);
      this.loadItems();
    };
    if (leaving) {
      void this.keepalive
        .send(`/api/v1/todo/lists/${list}/purge`, 'POST', { itemIds: ids })
        .then(settled);
      return;
    }
    this.service.purge(list, ids).subscribe({
      next: () => settled(true),
      error: (error: unknown) => {
        // the list went meanwhile: nothing to purge
        if (error instanceof HttpErrorResponse && error.status === 404) {
          settled(true);
          return;
        }
        if (!this.destroyed) {
          this.purgeError.set(apiErrorMessage(error, this.i18n.t('todo.list.not_purged')));
        }
        settled(false);
      },
    });
  }

  private show(ids: readonly number[]): void {
    this.hidden.update((hidden) => new Set([...hidden].filter((id) => !ids.includes(id))));
  }

  // a change of a task: its answer shows at once, a failure says why in its row; the tasks load
  // again either way, in their order
  private send(request: Observable<TodoItem>, failure: TextKey, id: number): void {
    this.itemError.clear();
    const settled = () => {
      this.marking.update((marking) => {
        const rest = new Map(marking);
        rest.delete(id);
        return rest;
      });
      this.loadItems();
    };
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (answered) => {
        this.loadedItems.update((items) =>
          items.map((each) => (each.id === answered.id ? answered : each)),
        );
        settled();
      },
      error: (error: unknown) => {
        this.itemError.set(id, apiErrorMessage(error, this.i18n.t(failure)));
        settled();
      },
    });
  }

  private loadItems(): void {
    if (this.destroyed) return;
    this.loadingItems?.unsubscribe();
    const list = this.open.shownId();
    if (list === null) {
      this.loadedItems.set([]);
      return;
    }
    this.loadingItems = this.service
      .items(list)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => {
          this.today.set(today());
          this.loadedItems.set(items);
          this.loaded.set(true);
          this.itemsError.set(null);
        },
        error: (error: unknown) =>
          this.itemsError.set(apiErrorMessage(error, this.i18n.t('todo.list.items_not_loaded'))),
      });
  }
}
