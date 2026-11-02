import { Component, computed, input } from '@angular/core';

// the mark of a list: its emoji, or the first letter of its name in a circle, as the android app
// shows it
@Component({
  selector: 'app-list-icon',
  host: {
    class:
      'd-inline-flex flex-shrink-0 align-items-center justify-content-center rounded-circle fw-semibold',
    '[class.list-icon-letter]': '!icon()',
    '[style.width.px]': 'size()',
    '[style.height.px]': 'size()',
    '[style.font-size.px]': 'size() * (icon() ? 0.7 : 0.45)',
    'aria-hidden': 'true',
  },
  styles: `
    :host(.list-icon-letter) {
      background: var(--bs-primary-bg-subtle);
      color: var(--bs-primary-text-emphasis);
    }
  `,
  template: `{{ icon() ?? letter() }}`,
})
export class ListIcon {
  readonly name = input.required<string>();
  // missing in the answer of a list without one (GroceryListResponse)
  readonly icon = input<string | null | undefined>(null);
  readonly size = input(40);

  // the first character as the user sees it, also one of several code units
  protected readonly letter = computed(() => [...this.name().trim()][0]?.toUpperCase() ?? '');
}
