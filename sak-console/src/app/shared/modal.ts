import { Component, input, output } from '@angular/core';

// a bootstrap modal shown while its parent renders it; escape, the backdrop and the close button
// ask the parent to close it
@Component({
  selector: 'app-modal',
  host: { '(document:keydown.escape)': 'closed.emit()' },
  template: `
    <div class="modal-backdrop show" (click)="closed.emit()"></div>
    <div class="modal d-block" tabindex="-1" role="dialog" aria-modal="true" [attr.aria-label]="title()">
      <div class="modal-dialog modal-dialog-centered">
        <div class="modal-content">
          <div class="modal-header">
            <h2 class="modal-title h5">{{ title() }}</h2>
            <button type="button" class="btn-close" aria-label="Close" (click)="closed.emit()"></button>
          </div>
          <ng-content />
        </div>
      </div>
    </div>
  `,
})
export class Modal {
  readonly title = input.required<string>();
  readonly closed = output<void>();
}
