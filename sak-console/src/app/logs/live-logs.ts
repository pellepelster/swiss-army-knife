import { DatePipe } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { SitesService } from '../sites/sites.service';
import { ALL_SITES, LiveLogError, LogEntry, LogsService } from './logs.service';

// the most entries the live view keeps, the oldest ones are dropped
export const MAX_ENTRIES = 500;

export const PAGE_SIZES = [25, 50, 100];

const DEFAULT_PAGE_SIZE = 50;

// the page size is remembered per browser
const PAGE_SIZE_KEY = 'sak.live.pageSize';

// how many page numbers the pagination shows around the current page
const PAGE_LINKS = 5;

function loadPageSize(): number {
  try {
    const stored = Number(localStorage.getItem(PAGE_SIZE_KEY));
    return PAGE_SIZES.includes(stored) ? stored : DEFAULT_PAGE_SIZE;
  } catch {
    return DEFAULT_PAGE_SIZE;
  }
}

// the first reconnect waits this long, every further one twice as long up to the maximum
const RECONNECT_DELAY_MS = 3000;

const MAX_RECONNECT_DELAY_MS = 60000;

const SEARCH_DEBOUNCE_MS = 300;

// a session whose role does not allow the logs, retrying does not help
const FORBIDDEN = 'Your role does not allow to see the logs.';

type State = 'connecting' | 'following' | 'reconnecting' | 'paused' | 'searching' | 'search';

// the requests of a site or all sites as they arrive; a search text stops following and
// searches the stored entries, follow drops the search and follows again
@Component({
  selector: 'app-live-logs',
  imports: [DatePipe],
  styles: `
    .live-controls {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
    }

    .live-search {
      width: 18rem;
      max-width: 100%;
    }

    .live-table td {
      white-space: nowrap;
    }

    .live-path {
      max-width: 40rem;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .live-footer {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
      padding: 0.5rem 0.75rem;
      border-top: 1px solid var(--bs-border-color);
    }
  `,
  template: `
    <div class="page">
      <div class="page-header d-flex align-items-end justify-content-between">
        <div>
          <div class="label-mono">Logs</div>
          <h1 class="mb-1">Live</h1>
        </div>
        <div class="live-controls">
          <label for="live-site" class="form-label mb-0">Site</label>
          <select
            id="live-site"
            class="form-select form-select-sm w-auto font-mono"
            (change)="selectSite($any($event.target).value)"
          >
            <option [value]="allSites" [selected]="site() === allSites">All sites</option>
            @for (choice of siteChoices(); track choice.id) {
              <option [value]="choice.id" [selected]="choice.id === site()">{{ choice.id }}</option>
            }
          </select>
          <input
            class="form-control form-control-sm live-search"
            type="search"
            placeholder="Search all logs…"
            aria-label="Search"
            [value]="searchText()"
            (input)="typed($any($event.target).value)"
          />
          <button
            type="button"
            class="btn btn-sm"
            [class.btn-primary]="!following()"
            [class.btn-outline-secondary]="following()"
            (click)="toggle()"
            data-testid="follow-toggle"
          >
            @if (following()) {
              <span aria-hidden="true">❚❚</span> Pause
            } @else {
              <span aria-hidden="true">▶</span> Follow
            }
          </button>
          <span class="status {{ stateClass() }}" data-testid="live-state">{{ stateLabel() }}</span>
        </div>
      </div>

      @if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      }

      <div class="card">
        <table class="table table-sm mb-0 live-table" aria-label="Log entries">
          <thead>
            <tr>
              <th scope="col">Time</th>
              <th scope="col">Site</th>
              <th scope="col">Path</th>
              <th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            @for (entry of visible(); track $index) {
              <tr>
                <td class="font-mono" [title]="entry.time">{{ entry.time | date: 'yyyy-MM-dd HH:mm:ss' }}</td>
                <td class="font-mono">{{ entry.site }}</td>
                <td class="font-mono live-path" [title]="entry.path">{{ entry.path }}</td>
                <td class="font-mono {{ statusClass(entry.status) }}">{{ statusLabel(entry.status) }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4" class="text-center text-secondary py-4">{{ emptyText() }}</td>
              </tr>
            }
          </tbody>
        </table>
        <div class="live-footer">
          <div class="d-flex align-items-center gap-2">
            <label for="live-page-size" class="form-label mb-0">Rows</label>
            <select
              id="live-page-size"
              class="form-select form-select-sm w-auto"
              (change)="selectPageSize(+$any($event.target).value)"
            >
              @for (size of pageSizes; track size) {
                <option [value]="size" [selected]="size === pageSize()">{{ size }}</option>
              }
            </select>
            @if (shown().length > 0) {
              <span class="text-secondary small font-mono" data-testid="live-range">
                {{ firstShown() }}–{{ lastShown() }} of {{ shown().length }}
              </span>
            }
          </div>
          <nav aria-label="Pages">
            <ul class="pagination pagination-sm mb-0">
              <li class="page-item" [class.disabled]="page() === 1">
                <button type="button" class="page-link" aria-label="First page" [disabled]="page() === 1" (click)="goTo(1)">«</button>
              </li>
              <li class="page-item" [class.disabled]="page() === 1">
                <button type="button" class="page-link" aria-label="Previous page" [disabled]="page() === 1" (click)="goTo(page() - 1)">‹</button>
              </li>
              @for (number of pageNumbers(); track number) {
                <li class="page-item" [class.active]="number === page()">
                  <button
                    type="button"
                    class="page-link"
                    [attr.aria-current]="number === page() ? 'page' : null"
                    (click)="goTo(number)"
                  >
                    {{ number }}
                  </button>
                </li>
              }
              <li class="page-item" [class.disabled]="page() === pages()">
                <button type="button" class="page-link" aria-label="Next page" [disabled]="page() === pages()" (click)="goTo(page() + 1)">›</button>
              </li>
              <li class="page-item" [class.disabled]="page() === pages()">
                <button type="button" class="page-link" aria-label="Last page" [disabled]="page() === pages()" (click)="goTo(pages())">»</button>
              </li>
            </ul>
          </nav>
        </div>
      </div>
    </div>
  `,
})
export class LiveLogs {
  private readonly logs = inject(LogsService);
  private readonly sites = inject(SitesService);
  private readonly router = inject(Router);

  // the route parameter and the search text in the query
  readonly site = input.required<string>();
  readonly q = input<string>();

  protected readonly allSites = ALL_SITES;
  protected readonly siteChoices = computed(() => this.sites.sites()?.sites ?? []);

  protected readonly searchText = signal('');
  protected readonly paused = signal(false);
  protected readonly state = signal<State>('connecting');
  protected readonly error = signal<string | null>(null);

  private readonly followed = signal<LogEntry[]>([]);
  private readonly found = signal<LogEntry[]>([]);

  private readonly searching = computed(() => (this.q() ?? '').trim().length > 0);

  protected readonly following = computed(() => !this.searching() && !this.paused());

  protected readonly shown = computed(() => (this.searching() ? this.found() : this.followed()));

  protected readonly pageSizes = PAGE_SIZES;
  protected readonly pageSize = signal(loadPageSize());
  private readonly requestedPage = signal(1);

  protected readonly pages = computed(() =>
    Math.max(1, Math.ceil(this.shown().length / this.pageSize())),
  );

  // the last page instead of the requested one while entries were dropped
  protected readonly page = computed(() => Math.min(this.requestedPage(), this.pages()));

  protected readonly visible = computed(() => {
    const start = (this.page() - 1) * this.pageSize();
    return this.shown().slice(start, start + this.pageSize());
  });

  protected readonly firstShown = computed(() => (this.page() - 1) * this.pageSize() + 1);

  protected readonly lastShown = computed(() =>
    Math.min(this.page() * this.pageSize(), this.shown().length),
  );

  // up to PAGE_LINKS page numbers with the current page in the middle
  protected readonly pageNumbers = computed(() => {
    const count = Math.min(PAGE_LINKS, this.pages());
    const first = Math.min(
      Math.max(1, this.page() - Math.floor(PAGE_LINKS / 2)),
      this.pages() - count + 1,
    );
    return Array.from({ length: count }, (_, index) => first + index);
  });

  protected readonly stateLabel = computed(
    () =>
      ({
        connecting: 'Connecting',
        following: 'Live',
        reconnecting: 'Reconnecting',
        paused: 'Paused',
        searching: 'Searching',
        search: `${this.found().length} found`,
      })[this.state()],
  );

  protected readonly stateClass = computed(
    () =>
      ({
        connecting: 'status-provisioning',
        following: 'status-running',
        reconnecting: 'status-degraded',
        paused: 'status-stopped',
        searching: 'status-provisioning',
        search: 'status-stopped',
      })[this.state()],
  );

  protected readonly emptyText = computed(() =>
    this.searching() ? 'No matching entries.' : 'Waiting for requests…',
  );

  private debounce?: ReturnType<typeof setTimeout>;

  constructor() {
    this.sites.load();

    // a pending search must not navigate once the view is gone
    inject(DestroyRef).onDestroy(() => clearTimeout(this.debounce));

    // the search box shows the search of the url
    effect(() => {
      const q = this.q() ?? '';
      untracked(() => this.searchText.set(q));
    });

    // follows while there is neither a search nor a pause, from the start again every time
    effect((onCleanup) => {
      if (!this.following()) {
        return;
      }
      const site = this.site();
      untracked(() => {
        this.followed.set([]);
        this.error.set(null);
        this.requestedPage.set(1);
      });
      const subscription = this.follow(site);
      onCleanup(() => subscription.unsubscribe());
    });

    effect((onCleanup) => {
      if (!this.searching()) {
        return;
      }
      const site = this.site();
      const text = this.q()!.trim();
      untracked(() => {
        this.state.set('searching');
        this.error.set(null);
        this.requestedPage.set(1);
      });
      const subscription = this.logs.search(site, text).subscribe({
        next: (entries) => {
          this.found.set(entries);
          this.state.set('search');
        },
        error: (error: unknown) => {
          this.found.set([]);
          this.state.set('search');
          this.error.set(
            error instanceof HttpErrorResponse && error.status === 403
              ? FORBIDDEN
              : 'The logs could not be searched, they may not be available right now.',
          );
        },
      });
      onCleanup(() => subscription.unsubscribe());
    });

    effect(() => {
      if (!this.searching() && this.paused()) {
        untracked(() => this.state.set('paused'));
      }
    });
  }

  // reconnects when the stream ends or breaks, waiting longer after every failed attempt, until
  // unsubscribed; why it can not follow is shown until a stream is open again
  private follow(site: string): Subscription {
    const subscription = new Subscription();
    let attempts = 0;
    const connect = (state: State) => {
      this.state.set(state);
      subscription.add(
        this.logs
          .follow(site, () => {
            attempts = 0;
            this.error.set(null);
            this.state.set('following');
          })
          .subscribe({
            // newest first, one update per chunk
            next: (chunk) =>
              this.followed.update((entries) => [...[...chunk].reverse(), ...entries].slice(0, MAX_ENTRIES)),
            error: (error: unknown) => {
              if (error instanceof LiveLogError && error.status === 403) {
                this.error.set(FORBIDDEN);
                this.state.set('paused');
                return;
              }
              this.error.set(
                error instanceof LiveLogError && error.status === 503
                  ? 'The logs can not be followed right now, they are not available or too many live views are open.'
                  : 'The connection to the logs broke.',
              );
              retry();
            },
            complete: () => retry(),
          }),
      );
    };
    const retry = () => {
      this.state.set('reconnecting');
      const delay = Math.min(RECONNECT_DELAY_MS * 2 ** attempts, MAX_RECONNECT_DELAY_MS);
      attempts++;
      subscription.add(timer(delay).subscribe(() => connect('reconnecting')));
    };
    connect('connecting');
    return subscription;
  }

  protected goTo(page: number): void {
    this.requestedPage.set(Math.min(Math.max(1, page), this.pages()));
  }

  protected selectPageSize(size: number): void {
    this.pageSize.set(size);
    this.requestedPage.set(1);
    try {
      localStorage.setItem(PAGE_SIZE_KEY, `${size}`);
    } catch {
      // the size is only kept for this page then
    }
  }

  protected typed(text: string): void {
    this.searchText.set(text);
    clearTimeout(this.debounce);
    this.debounce = setTimeout(() => this.search(text), SEARCH_DEBOUNCE_MS);
  }

  private search(text: string): void {
    void this.router.navigate([], {
      queryParams: { q: text.trim() || null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  // pause while following, otherwise drop the search and follow again
  protected toggle(): void {
    if (this.following()) {
      this.paused.set(true);
      return;
    }
    clearTimeout(this.debounce);
    this.paused.set(false);
    this.searchText.set('');
    this.search('');
  }

  // a site is followed from the start, a pause does not carry over to it
  protected selectSite(site: string): void {
    this.paused.set(false);
    void this.router.navigate(['/logs/live', site], { queryParamsHandling: 'preserve' });
  }

  protected statusLabel(status: number | null): string {
    return status === null || status === 0 ? '–' : `${status}`;
  }

  // the status colors of the theme, its yellow is too light for text on paper
  protected statusClass(status: number | null): string {
    if (status === null || status === 0) return 'text-secondary';
    if (status >= 500) return 'status-text-failed';
    if (status >= 400) return 'status-text-provisioning';
    if (status >= 300) return 'status-text-stopped';
    return 'status-text-running';
  }
}
