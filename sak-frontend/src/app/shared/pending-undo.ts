import { signal } from '@angular/core';

// one change at a time that the ui shows at once and the backend learns only when `ms` are up,
// unless it is undone first (UX-4.2); a new change sends the one before at once. `leaving` tells the
// commit that the page goes away, e.g. to send it with keepalive
export class PendingUndo<T> {
  // what can be undone now, null when nothing can
  readonly current = signal<T | null>(null);

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
