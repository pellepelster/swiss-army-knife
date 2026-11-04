import { signal } from '@angular/core';

// the error of a call made from a row of a list (or a card of a wall), shown in that row (UX-15);
// one at a time, the next call from any row clears it. A row that is not shown, e.g. one that was
// removed, leaves its error to the top of the page (stray)
export class RowError<K> {
  private readonly current = signal<{ row: K; message: string } | null>(null);

  // the error of the row, null when it has none
  of(row: K): string | null {
    const current = this.current();
    return current !== null && current.row === row ? current.message : null;
  }

  // the error while its row is not shown, null otherwise
  stray(shown: (row: K) => boolean): string | null {
    const current = this.current();
    return current !== null && !shown(current.row) ? current.message : null;
  }

  set(row: K, message: string): void {
    this.current.set({ row, message });
  }

  clear(): void {
    this.current.set(null);
  }
}
