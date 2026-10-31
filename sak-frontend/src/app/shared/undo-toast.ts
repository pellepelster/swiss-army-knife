import { Component, input, output } from '@angular/core';

// how long what was removed can be brought back before the backend removes it
export const UNDO_DELETE_MS = 5000;

// tells what was removed and offers to bring it back while the bar shrinks; centered at the bottom,
// on a phone above the floating buttons of a page that has them. Shown anew for every removal, the
// bar starts again
@Component({
  selector: 'app-undo-toast',
  template: `
    <div
      class="undo-toast toast show position-fixed bottom-0 start-50 translate-middle-x mb-3"
      [class.above-buttons]="aboveButtons()"
      role="status"
    >
      <div class="d-flex align-items-center gap-2 p-2 ps-3">
        <span class="flex-grow-1">{{ text() }}</span>
        <button type="button" class="btn btn-sm btn-primary" (click)="undo.emit()">Undo</button>
      </div>
      <div class="progress rounded-0" aria-hidden="true">
        <div class="undo-toast-time progress-bar" [style.animation-duration.ms]="ms()"></div>
      </div>
    </div>
  `,
  styles: `
    .undo-toast {
      z-index: 1030;

      // bootstrap sets these on .progress itself
      .progress {
        --bs-progress-height: 4px;
        --bs-progress-bar-bg: var(--ink);
      }
    }

    .undo-toast-time {
      width: 100%;
      animation-name: undo-time-left;
      animation-timing-function: linear;
      animation-fill-mode: forwards;
    }

    @keyframes undo-time-left {
      to {
        width: 0;
      }
    }

    // on a phone as wide as the screen allows, above the floating buttons of a page that has them
    @media (max-width: 767.98px) {
      .undo-toast {
        width: calc(100% - 2rem);
      }

      .undo-toast.above-buttons {
        margin-bottom: 11rem !important;
      }
    }
  `,
})
export class UndoToast {
  // what was removed, e.g. "Note deleted"
  readonly text = input.required<string>();
  // the page has floating buttons at the bottom on a phone, e.g. the notes
  readonly aboveButtons = input(false);
  // how long it can be undone, the bar takes as long
  readonly ms = input(UNDO_DELETE_MS);
  readonly undo = output<void>();
}
