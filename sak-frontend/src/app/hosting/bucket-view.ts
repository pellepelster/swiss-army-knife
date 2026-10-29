import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
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
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/static-hosting"
        aria-label="All buckets"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break font-monospace">{{ name() }}</h1>
      @if (store.bucket()) {
        @if (confirming()) {
          <button class="btn btn-danger" type="button" (click)="delete()">Delete</button>
          <button class="btn btn-outline-secondary" type="button" (click)="confirming.set(false)">
            Keep
          </button>
        } @else {
          <a class="btn btn-primary" [routerLink]="['/static-hosting', name(), 'edit']">
            <i class="fa-solid fa-pen" aria-hidden="true"></i> Edit
          </a>
          <button
            class="btn btn-outline-secondary"
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
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (store.bucket(); as bucket) {
      <dl class="card card-body flex-row flex-wrap gap-4 mb-3" aria-label="Overview">
        <div>
          <dt class="small text-uppercase text-body-secondary fw-normal">Created</dt>
          <dd class="mb-0 fw-semibold" data-testid="created">
            {{ bucket.createdAt | localDateTime: 'medium' : 'short' }}
          </dd>
        </div>
        @if (bucket.updatedAt; as updatedAt) {
          <div>
            <dt class="small text-uppercase text-body-secondary fw-normal">Updated</dt>
            <dd class="mb-0 fw-semibold" data-testid="updated">
              {{ updatedAt | localDateTime: 'medium' : 'short' }}
            </dd>
          </div>
        }
        @if (bucket.statistics.failed) {
          <div>
            <dt class="small text-uppercase text-body-secondary fw-normal">Objects</dt>
            <dd class="mb-0 fw-semibold text-body-secondary">not counted</dd>
          </div>
        } @else if (bucket.statistics.computing) {
          <div>
            <dt class="small text-uppercase text-body-secondary fw-normal">Objects</dt>
            <dd class="mb-0 fw-semibold text-body-secondary fst-italic">counting…</dd>
          </div>
        } @else {
          <div>
            <dt class="small text-uppercase text-body-secondary fw-normal">Objects</dt>
            <dd class="mb-0 fw-semibold" data-testid="objects">
              {{ formatCount(bucket.statistics.objects!) }}
            </dd>
          </div>
          <div>
            <dt class="small text-uppercase text-body-secondary fw-normal">Size</dt>
            <dd class="mb-0 fw-semibold" data-testid="bytes">
              {{ formatBytes(bucket.statistics.bytes!) }}
            </dd>
          </div>
        }
      </dl>

      <nav class="nav nav-underline mb-3" aria-label="Details">
        <a
          class="nav-link"
          routerLink="configuration"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          >Configuration</a
        >
        <a
          class="nav-link"
          routerLink="credentials"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          >Credentials</a
        >
        <a
          class="nav-link"
          routerLink="publishing"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          >Publishing</a
        >
        <a class="nav-link" routerLink="logs" routerLinkActive="active" ariaCurrentWhenActive="page"
          >Logs</a
        >
      </nav>
      <router-outlet />
    } @else if (!store.error()) {
      <p class="text-body-secondary">Loading…</p>
    }
  `,
})
export class BucketView {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(BucketsService);
  private readonly router = inject(Router);
  protected readonly store = inject(BucketStore);

  // the route parameter
  readonly name = input.required<string>();

  protected readonly confirming = signal(false);

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;

  constructor() {
    // the bucket of the route, again when the route names another one
    effect(() => {
      const name = this.name();
      untracked(() => this.store.load(name));
    });
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
