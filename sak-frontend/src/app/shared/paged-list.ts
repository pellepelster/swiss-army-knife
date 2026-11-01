import {
  DestroyRef,
  effect,
  inject,
  numberAttribute,
  Signal,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { Observable, Subscription, timer } from 'rxjs';
import { lastPageOffset, Page, Pagination } from './api';

// the offset query parameter of a list as its input: where the page shown starts, 0 when it is
// missing or no number (WEB-1)
export const pageOffset = (value: unknown) => Math.max(0, numberAttribute(value, 0));

export interface PagedListOptions<T> {
  // the route of the list, where its page is kept in the address
  path: string;
  // the offset input of the list's component
  offset: Signal<number>;
  page: (offset: number) => Observable<Page<T>>;
  // what to tell when a page could not be loaded
  error: (error: unknown) => string;
  // asks again for the page after `ms` while `while` holds for its items, e.g. while they are
  // still being counted
  again?: { while: (items: T[]) => boolean; ms: number };
}

// a list shown a page at a time, the page kept in the address (WEB-1): it loads the page of the
// offset, anew when the offset changes; a page that is gone, e.g. after its last item was deleted,
// leads to the last page with items. Called in the injection context of the list's component
export function pagedList<T>(options: PagedListOptions<T>) {
  const destroyRef = inject(DestroyRef);
  const router = inject(Router);

  const items = signal<T[]>([]);
  const loaded = signal(false);
  const error = signal<string | null>(null);
  const pagination = signal<Pagination | null>(null);
  // the loading of the page shown or the wait before asking again, replaced by the next one
  let loading: Subscription | undefined;

  const goTo = (offset: number) =>
    void router.navigate([options.path], { queryParams: { offset: offset > 0 ? offset : null } });

  const load = (offset: number): void => {
    loading?.unsubscribe();
    loading = options
      .page(offset)
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe({
        next: (page) => {
          if (page.items.length === 0 && offset > 0) {
            goTo(lastPageOffset(page.pagination));
            return;
          }
          error.set(null);
          items.set(page.items);
          pagination.set(page.pagination);
          loaded.set(true);
          if (options.again?.while(page.items)) {
            loading = timer(options.again.ms)
              .pipe(takeUntilDestroyed(destroyRef))
              .subscribe(() => load(offset));
          }
        },
        error: (failure: unknown) => error.set(options.error(failure)),
      });
  };

  effect(() => {
    const offset = options.offset();
    untracked(() => load(offset));
  });

  return {
    items: items.asReadonly(),
    loaded: loaded.asReadonly(),
    pagination: pagination.asReadonly(),
    // what failed last, set also by what the list does with its items, e.g. a delete
    error,
    goTo,
    // the page shown again, e.g. after one of its items was deleted
    reload: () => load(options.offset()),
  };
}
