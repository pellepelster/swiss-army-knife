import { Component, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { apiErrorMessage } from '../shared/api';
import { formatBytes, formatCount } from '../shared/format';
import { LocalDateTimePipe } from '../shared/local-date-time.pipe';
import { BucketStore } from './bucket-store';
import { BucketsService } from './buckets.service';

// one bucket: its overview on top, its details in the tabs below, the first one its configuration
@Component({
  selector: 'app-bucket-view',
  imports: [LocalDateTimePipe, RouterLink, RouterLinkActive, RouterOutlet],
  providers: [BucketStore],
  styleUrls: ['../shared/app-parts.scss', '../shared/app-page.scss', '../shared/detail-view.scss'],
  styles: `
    .button-danger {
      background: var(--danger);
      color: var(--sheet);
    }
  `,
  template: `
    <header class="toolbar">
      <a class="back" routerLink="/static-hosting" aria-label="All buckets"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="title mono">{{ name() }}</h1>
      @if (store.bucket()) {
        @if (confirming()) {
          <button class="button button-danger" type="button" (click)="delete()">Delete</button>
          <button class="button button-quiet" type="button" (click)="confirming.set(false)">
            Keep
          </button>
        } @else {
          <a class="button" [routerLink]="['/static-hosting', name(), 'edit']">
            <i class="fa-solid fa-pen" aria-hidden="true"></i> Edit
          </a>
          <button
            class="button button-quiet"
            type="button"
            [attr.aria-label]="'Delete the bucket ' + name()"
            (click)="confirming.set(true)"
          >
            <i class="fa-solid fa-trash" aria-hidden="true"></i> Delete
          </button>
        }
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
        @if (bucket.updatedAt; as updatedAt) {
          <div>
            <dt>Updated</dt>
            <dd data-testid="updated">{{ updatedAt | localDateTime: 'medium' : 'short' }}</dd>
          </div>
        }
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
        <a
          class="tab"
          routerLink="publishing"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          >Publishing</a
        >
        <a class="tab" routerLink="logs" routerLinkActive="active" ariaCurrentWhenActive="page"
          >Logs</a
        >
      </nav>
      <router-outlet />
    }
  `,
})
export class BucketView implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(BucketsService);
  private readonly router = inject(Router);
  protected readonly store = inject(BucketStore);

  // the route parameter
  readonly name = input.required<string>();

  protected readonly confirming = signal(false);

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;

  ngOnInit(): void {
    this.store.load(this.name());
  }

  // its objects stay, the name stays taken
  protected delete(): void {
    this.confirming.set(false);
    this.service
      .delete(this.name())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigate(['/static-hosting']),
        error: (error: unknown) =>
          this.store.error.set(
            apiErrorMessage(error, `The bucket ${this.name()} could not be deleted.`, {
              403: 'Your role does not allow to delete buckets.',
            }),
          ),
      });
  }
}
