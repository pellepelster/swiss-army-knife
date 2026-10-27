import { Component, computed, input, output } from '@angular/core';
import { Pagination } from './api';

// the pages of a list: where the one shown is in the whole list, the one before and the one after;
// nothing while the list fits on one page
@Component({
  selector: 'app-pagination',
  template: `
    @if (pagination(); as page) {
      @if (page.total > page.limit || page.offset > 0) {
        <nav
          class="pagination d-flex flex-wrap align-items-center justify-content-center gap-3 my-3"
          [attr.aria-label]="label()"
        >
          <button
            class="btn btn-outline-secondary btn-sm"
            type="button"
            aria-label="Previous page"
            [disabled]="page.offset === 0"
            (click)="offsetChange.emit(previous())"
          >
            <i class="fa-solid fa-chevron-left" aria-hidden="true"></i> Previous
          </button>
          <span class="text-body-secondary" data-testid="range">{{ range() }}</span>
          <button
            class="btn btn-outline-secondary btn-sm"
            type="button"
            aria-label="Next page"
            [disabled]="page.offset + page.limit >= page.total"
            (click)="offsetChange.emit(page.offset + page.limit)"
          >
            Next <i class="fa-solid fa-chevron-right" aria-hidden="true"></i>
          </button>
        </nav>
      }
    }
  `,
})
export class PaginationControl {
  readonly pagination = input<Pagination | null>(null);

  // what is paged, e.g. "Pages of the buckets"
  readonly label = input('Pages');

  // where the page asked for starts
  readonly offsetChange = output<number>();

  protected readonly previous = computed(() => {
    const page = this.pagination();
    return page ? Math.max(0, page.offset - page.limit) : 0;
  });

  // e.g. 51–100 of 230
  protected readonly range = computed(() => {
    const page = this.pagination();
    if (!page || page.total === 0) return '';
    const last = Math.min(page.offset + page.limit, page.total);
    return `${page.offset + 1}–${last} of ${page.total}`;
  });
}
