import { computed, DestroyRef, inject, Signal, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { debounceTime, filter, Subscription } from 'rxjs';
import { I18n } from '../i18n/i18n';
import { apiErrorMessage } from './api';
import { LIST_APP } from './list-app';
import { CHANGES_DEBOUNCE_MS, EVERYTHING } from './list-changes';
import { ListsService, SharedList } from './list-service';

// the list a page of a list-based app (LIST_APP) shows, by the id of its route, among the lists of
// the user; a list that is gone leads to all lists. A change of the list, or of everything after the
// stream was away, loads the lists again and calls changed, e.g. to load the items too (LIST-4).
// Created in an injection context, e.g. as a field of the page
export class OpenList<L extends SharedList> {
  private readonly app = inject(LIST_APP);
  private readonly service = inject(this.app.service) as ListsService<L>;
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);
  private loading?: Subscription;

  readonly lists = signal<L[]>([]);
  // the lists could not be loaded
  readonly error = signal<string | null>(null);
  // the list shown, null until the lists are loaded
  readonly shown: Signal<L | null>;
  // the id of the list shown: unlike shown, the same while the lists load again, so what follows
  // the list itself (its items, what can be undone on it) starts anew only for another list
  readonly shownId: Signal<number | null>;

  constructor(
    private readonly id: Signal<string>,
    changed: () => void,
  ) {
    this.shown = computed(() => this.lists().find((list) => String(list.id) === id()) ?? null);
    this.shownId = computed(() => this.shown()?.id ?? null);
    this.load();
    inject(this.app.changes)
      .changes()
      .pipe(
        filter((changedId) => changedId === EVERYTHING || String(changedId) === this.id()),
        debounceTime(CHANGES_DEBOUNCE_MS),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.load();
        changed();
      });
  }

  load(): void {
    this.loading?.unsubscribe();
    this.loading = this.service
      .lists()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (lists) => {
          this.error.set(null);
          this.lists.set(lists);
          if (!lists.some((list) => String(list.id) === this.id())) {
            void this.router.navigate([this.app.route], { replaceUrl: true });
          }
        },
        error: (error: unknown) =>
          this.error.set(
            apiErrorMessage(error, this.i18n.t(this.app.texts.listsNotLoaded), {
              403: this.i18n.t(this.app.texts.listsForbidden),
            }),
          ),
      });
  }
}
