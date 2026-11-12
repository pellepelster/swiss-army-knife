import { DOCUMENT } from '@angular/common';
import { computed, DestroyRef, inject, signal } from '@angular/core';

// calls leave when the page goes away (pagehide) or into the background (visibilitychange to
// hidden): a phone ends an app in the background without pagehide. Listens until destroyRef ends;
// call it in an injection context or give the document
export function whenLeaving(
  destroyRef: DestroyRef,
  leave: () => void,
  document: Document = inject(DOCUMENT),
): void {
  const window = document.defaultView;
  const hidden = () => {
    if (document.visibilityState === 'hidden') leave();
  };
  window?.addEventListener('pagehide', leave);
  document.addEventListener('visibilitychange', hidden);
  destroyRef.onDestroy(() => {
    window?.removeEventListener('pagehide', leave);
    document.removeEventListener('visibilitychange', hidden);
  });
}

// one change at a time that the ui shows at once and the backend learns only when `ms` are up,
// unless it is undone first (UX-4.2); a new change sends the one before at once. `leaving` tells the
// commit that the page goes away, e.g. to send it with keepalive
export class PendingUndo<T> {
  // what can be undone now, null when nothing can
  readonly current = signal<T | null>(null);
  // the same as a list for `@for (each of undoable(); track each)`, so the toast and the bar showing
  // the time left start anew for the next change
  readonly undoable = computed(() => {
    const current = this.current();
    return current === null ? [] : [current];
  });

  private pending: {
    timer: ReturnType<typeof setTimeout>;
    commit: (leaving: boolean) => void;
    undo: () => void;
  } | null = null;

  constructor(private readonly ms: number) {}

  start(value: T, commit: (leaving: boolean) => void, undo: () => void): void {
    this.commit();
    const timer = setTimeout(() => this.commit(), this.ms);
    this.pending = { timer, commit, undo };
    this.current.set(value);
  }

  // sends what is pending now, nothing when nothing is
  commit(leaving = false): void {
    const pending = this.take();
    pending?.commit(leaving);
  }

  // what is pending goes to the backend at once when the page goes away or into the background
  // (leaving, see whenLeaving) and when destroyRef ends (not leaving: the app goes on, e.g. to
  // another page). Call it in an injection context or give the document
  commitWhenLeaving(destroyRef: DestroyRef, document: Document = inject(DOCUMENT)): void {
    whenLeaving(destroyRef, () => this.commit(true), document);
    destroyRef.onDestroy(() => this.commit());
  }

  // takes back what is pending, nothing when nothing is
  undo(): void {
    const pending = this.take();
    pending?.undo();
  }

  // forgets what is pending without sending it, e.g. when its page is destroyed
  dispose(): void {
    this.take();
  }

  private take() {
    const pending = this.pending;
    if (!pending) return null;
    clearTimeout(pending.timer);
    this.pending = null;
    this.current.set(null);
    return pending;
  }
}
