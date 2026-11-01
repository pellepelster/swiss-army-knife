import { afterNextRender, Component, ElementRef, input, output, viewChild } from '@angular/core';
import { TranslatePipe } from '../i18n/i18n';

// the inline question of a delete (UX-4.1, WEB-3): "Delete <name>?", then Delete and Keep as one
// group in the place of what asked. Keep is the safe default and has the focus
@Component({
  selector: 'app-confirm-delete',
  imports: [TranslatePipe],
  host: { class: 'd-flex flex-wrap align-items-center justify-content-end gap-2' },
  template: `
    <span class="small">{{ 'shared.confirm_delete' | t: { name: name() } }}</span>
    <div
      class="btn-group"
      role="group"
      [attr.aria-label]="'shared.delete_name' | t: { name: name() }"
    >
      <button
        type="button"
        class="btn btn-danger"
        [class.btn-sm]="small()"
        (click)="confirmed.emit()"
      >
        {{ 'shared.delete' | t }}
      </button>
      <button
        #keep
        type="button"
        class="btn btn-outline-secondary"
        [class.btn-sm]="small()"
        (click)="kept.emit()"
      >
        {{ 'shared.keep' | t }}
      </button>
    </div>
  `,
})
export class ConfirmDelete {
  // what goes, asked as "Delete <name>?"
  readonly name = input.required<string>();
  // small buttons, e.g. in a row of a list
  readonly small = input(false);

  readonly confirmed = output<void>();
  readonly kept = output<void>();

  private readonly keep = viewChild.required<ElementRef<HTMLButtonElement>>('keep');

  constructor() {
    afterNextRender(() => this.keep().nativeElement.focus());
  }
}
