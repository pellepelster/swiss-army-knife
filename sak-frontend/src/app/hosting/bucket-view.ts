import { Component, inject, input, OnInit } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { formatBytes, formatCount } from '../shared/format';
import { LocalDateTimePipe } from '../shared/local-date-time.pipe';
import { BucketStore } from './bucket-store';

// one bucket: its overview on top, its details in the tabs below, the first one its configuration
@Component({
  selector: 'app-bucket-view',
  imports: [LocalDateTimePipe, RouterLink, RouterLinkActive, RouterOutlet],
  providers: [BucketStore],
  styleUrls: ['./hosting.scss', './hosting-page.scss'],
  styles: `
    .overview {
      display: flex;
      flex-wrap: wrap;
      gap: 12px 32px;
      margin-bottom: 16px;
    }
    .overview div {
      display: grid;
      gap: 2px;
    }
    .overview dt {
      color: var(--muted);
      font-size: 0.8rem;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }
    .overview dd {
      margin: 0;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }
    .tabs {
      display: flex;
      gap: 4px;
      margin-bottom: 16px;
      border-bottom: 1px solid var(--line);
    }
    .tab {
      margin-bottom: -1px;
      padding: 8px 14px;
      border-bottom: 2px solid transparent;
      color: var(--muted);
      font-weight: 600;
      text-decoration: none;

      &.active {
        border-bottom-color: var(--ink);
        color: var(--ink);
      }
    }
  `,
  template: `
    <header class="toolbar">
      <a class="back" routerLink="/hosting" aria-label="All buckets"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="title mono">{{ name() }}</h1>
      @if (store.bucket()) {
        <a class="button" [routerLink]="['/hosting', name(), 'edit']">
          <i class="fa-solid fa-pen" aria-hidden="true"></i> Edit
        </a>
      }
    </header>

    @if (store.error(); as error) {
      <p class="error" role="alert">{{ error }}</p>
    }

    @if (store.bucket(); as bucket) {
      <dl class="overview sheet" aria-label="Overview">
        <div>
          <dt>Created</dt>
          <dd data-testid="created">{{ bucket.createdAt | localDateTime: 'medium' : 'short' }}</dd>
        </div>
        @if (bucket.statistics.failed) {
          <div>
            <dt>Objects</dt>
            <dd class="muted">not counted</dd>
          </div>
        } @else if (bucket.statistics.computing) {
          <div>
            <dt>Objects</dt>
            <dd class="counting">counting…</dd>
          </div>
        } @else {
          <div>
            <dt>Objects</dt>
            <dd data-testid="objects">{{ formatCount(bucket.statistics.objects!) }}</dd>
          </div>
          <div>
            <dt>Size</dt>
            <dd data-testid="bytes">{{ formatBytes(bucket.statistics.bytes!) }}</dd>
          </div>
          <div>
            <dt>Counted</dt>
            <dd>{{ bucket.statistics.computedAt | localDateTime: null : 'short' }}</dd>
          </div>
        }
      </dl>

      <nav class="tabs" aria-label="Details">
        <a
          class="tab"
          routerLink="configuration"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          >Configuration</a
        >
        <a
          class="tab"
          routerLink="credentials"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          >Credentials</a
        >
      </nav>
      <router-outlet />
    }
  `,
})
export class BucketView implements OnInit {
  protected readonly store = inject(BucketStore);

  // the route parameter
  readonly name = input.required<string>();

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;

  ngOnInit(): void {
    this.store.load(this.name());
  }
}
