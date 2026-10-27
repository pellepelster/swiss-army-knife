import { Component, inject, input, OnInit } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { EntityOverviewPanel, OverviewField } from '../shared/entity-overview-panel';
import { formatBytes, formatCount } from '../shared/format';
import { TabEntry, TabNav } from '../shared/tab-nav';
import { BucketStore } from './bucket-store';
import { LocalDateTimePipe } from '../shared/local-date-time.pipe';

const BUCKET_TABS: readonly TabEntry[] = [
  { path: 'configuration', label: 'Configuration' },
  { path: 'credentials', label: 'Credentials' },
];

// one bucket: its overview with the statistics on top, its details in the tabs below
@Component({
  selector: 'app-bucket',
  imports: [EntityOverviewPanel, LocalDateTimePipe, OverviewField, RouterLink, RouterOutlet, TabNav],
  providers: [BucketStore],
  template: `
    <div class="page">
      <div class="page-header d-flex flex-wrap align-items-end justify-content-between gap-2">
        <div>
          <h1 class="mb-1 font-mono">{{ name() }}</h1>
        </div>
        <a class="btn btn-outline-secondary" [routerLink]="['/s3/buckets', name(), 'edit']">Edit</a>
      </div>

      @if (store.error()) {
        <div class="alert alert-danger" role="alert">{{ store.error() }}</div>
      }

      @if (store.bucket(); as bucket) {
        <app-entity-overview-panel [name]="bucket.name" [created]="bucket.createdAt">
          @if (bucket.statistics.failed) {
            <app-overview-field label="Objects"
              ><span class="text-secondary fw-normal">could not be counted</span></app-overview-field
            >
          } @else if (bucket.statistics.computing) {
            <app-overview-field label="Objects">
              <span class="text-secondary fw-normal">
                <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                counting…
              </span>
            </app-overview-field>
          } @else {
            <app-overview-field label="Objects"
              ><span data-testid="objects">{{ formatCount(bucket.statistics.objects!) }}</span></app-overview-field
            >
            <app-overview-field label="Size"
              ><span data-testid="bytes">{{ formatBytes(bucket.statistics.bytes!) }}</span></app-overview-field
            >
            <app-overview-field label="Counted">{{
              bucket.statistics.computedAt | localDateTime: null : 'medium'
            }}</app-overview-field>
          }
        </app-entity-overview-panel>

        <app-tab-nav [tabs]="tabs" />
        <router-outlet />
      }
    </div>
  `,
})
export class BucketDetail implements OnInit {
  protected readonly store = inject(BucketStore);

  // the route parameter
  readonly name = input.required<string>();

  protected readonly tabs = BUCKET_TABS;
  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;

  ngOnInit(): void {
    this.store.load(this.name());
  }
}
