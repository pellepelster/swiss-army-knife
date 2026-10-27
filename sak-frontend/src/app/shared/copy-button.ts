import { Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, timer } from 'rxjs';

// how long the button says it copied
export const COPIED_MS = 2000;

// copies the text to the clipboard and says so for a moment; `icon` is a square icon button next to
// a value, `terminal` the pill in the bar of a code block. Where it may fail (the browser can
// refuse the clipboard) the place using it tells, failed says whether the last copy did
@Component({
  selector: 'app-copy-button',
  template: `
    <button
      type="button"
      [class]="
        look() === 'icon' ? 'btn btn-outline-secondary btn-sm' : 'btn btn-outline-light btn-sm'
      "
      [attr.aria-label]="label()"
      [title]="label()"
      (click)="copy()"
    >
      <i
        class="fa-solid"
        [class.fa-check]="copied()"
        [class.fa-copy]="!copied()"
        aria-hidden="true"
      ></i>
      @if (look() === 'terminal') {
        {{ copied() ? 'Copied' : 'Copy' }}
      }
    </button>
  `,
})
export class CopyButton {
  private readonly destroyRef = inject(DestroyRef);

  readonly text = input.required<string>();
  readonly label = input.required<string>();
  readonly look = input<'icon' | 'terminal'>('icon');

  // whether the last copy failed
  readonly failed = output<boolean>();

  protected readonly copied = signal(false);

  // the wait until the button no longer says it copied, replaced by the next copy
  private copiedTimer?: Subscription;

  protected async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.text());
      this.failed.emit(false);
      this.copied.set(true);
      this.copiedTimer?.unsubscribe();
      this.copiedTimer = timer(COPIED_MS)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => this.copied.set(false));
    } catch {
      this.failed.emit(true);
    }
  }
}
