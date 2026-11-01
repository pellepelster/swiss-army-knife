import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

// the header of a page: the arrow back to where the page was opened from, then what the header
// holds, its h1 and the actions of the page at the right (UX-10). On a <header>, so it stays one
@Component({
  selector: 'header[appPageHeader]',
  imports: [RouterLink],
  host: { class: 'd-flex flex-wrap align-items-center gap-2 py-3' },
  template: `
    @if (back(); as back) {
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        [routerLink]="back"
        [attr.aria-label]="backLabel()"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
    }
    <ng-content />
  `,
})
export class PageHeader {
  // where the arrow leads, none for a page without one
  readonly back = input<string | readonly unknown[] | null>(null);
  // what the arrow says, e.g. "All apps"
  readonly backLabel = input('');
}
