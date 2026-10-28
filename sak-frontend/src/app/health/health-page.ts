import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '../shared/api';
import { HealthService, HealthState, HealthStatus } from './health.service';

const STATES: Record<HealthState, { label: string; badge: string; icon: string }> = {
  HEALTHY: { label: 'Healthy', badge: 'text-bg-success', icon: 'fa-circle-check' },
  UNHEALTHY: { label: 'Unhealthy', badge: 'text-bg-danger', icon: 'fa-circle-xmark' },
  UNKNOWN: { label: 'Unknown', badge: 'text-bg-secondary', icon: 'fa-circle-question' },
};

// the status of every health check of the backend, checked anew when the page opens and on demand
@Component({
  selector: 'app-health-page',
  imports: [RouterLink],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/"
        aria-label="All apps"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">Health</h1>
      <button
        type="button"
        class="btn btn-outline-secondary"
        aria-label="Check again"
        title="Check again"
        [disabled]="checking()"
        (click)="check()"
      >
        <i class="fa-solid fa-rotate" [class.fa-spin]="checking()" aria-hidden="true"></i>
      </button>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (statuses(); as statuses) {
      <ul class="list-unstyled vstack gap-2 mb-0" aria-label="Health checks">
        @for (status of statuses; track status.name) {
          <li
            class="card card-body flex-row align-items-center gap-3"
            [attr.data-testid]="'health-' + status.name"
          >
            <div class="flex-grow-1 overflow-hidden">
              <span class="fw-semibold">{{ status.name }}</span>
              @if (status.message) {
                <p class="small text-body-secondary text-break mb-0 mt-1" data-testid="message">
                  {{ status.message }}
                </p>
              }
            </div>
            <span [class]="'badge flex-shrink-0 ' + states[status.state].badge" data-testid="state">
              <i [class]="'fa-solid ' + states[status.state].icon" aria-hidden="true"></i>
              {{ states[status.state].label }}
            </span>
          </li>
        } @empty {
          <li class="card card-body text-center py-4">There are no health checks.</li>
        }
      </ul>
    } @else if (!error()) {
      <p class="text-body-secondary">Checking…</p>
    }
  `,
})
export class HealthPage {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(HealthService);

  protected readonly states = STATES;

  protected readonly statuses = signal<HealthStatus[] | null>(null);
  protected readonly checking = signal(false);
  protected readonly error = signal<string | null>(null);

  // the check running, replaced by the next one
  private checks?: Subscription;

  constructor() {
    this.check();
  }

  protected check(): void {
    this.checks?.unsubscribe();
    this.checking.set(true);
    this.checks = this.service
      .statuses()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (statuses) => {
          this.error.set(null);
          this.statuses.set(statuses);
          this.checking.set(false);
        },
        error: (error: unknown) => {
          this.checking.set(false);
          this.error.set(
            apiErrorMessage(error, 'The health could not be checked.', {
              403: 'Your role does not allow to see the health.',
            }),
          );
        },
      });
  }
}
