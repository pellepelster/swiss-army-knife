import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { SitesService } from '../sites/sites.service';
import { ALL_SITES, LogEntry, LogsService } from './logs.service';

// the most entries the live view keeps, the oldest ones are dropped
export const MAX_ENTRIES = 500;

const RECONNECT_DELAY_MS = 3000;

const SEARCH_DEBOUNCE_MS = 300;

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
            @for (entry of shown(); track $index) {
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
      });
      const subscription = this.logs.search(site, text).subscribe({
        next: (entries) => {
          this.found.set(entries);
          this.state.set('search');
        },
        error: () => {
          this.found.set([]);
          this.state.set('search');
          this.error.set('The logs could not be searched, they may not be available right now.');
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

  // reconnects after a pause when the stream ends or breaks, until unsubscribed
  private follow(site: string): Subscription {
    const subscription = new Subscription();
    const connect = (state: State) => {
      this.state.set(state);
      subscription.add(
        this.logs
          .follow(site, () => this.state.set('following'))
          .subscribe({
            next: (entry) =>
              this.followed.update((entries) => [entry, ...entries].slice(0, MAX_ENTRIES)),
            error: () => retry(),
            complete: () => retry(),
          }),
      );
    };
    const retry = () => {
      this.state.set('reconnecting');
      subscription.add(timer(RECONNECT_DELAY_MS).subscribe(() => connect('reconnecting')));
    };
    connect('connecting');
    return subscription;
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
