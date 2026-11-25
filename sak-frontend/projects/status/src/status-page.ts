import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { apiErrorMessage, formatBytes, I18n, PageHeader, TextKey, TranslatePipe } from '@sak/core';
import { HealthState, HealthStatus, Status, StatusService } from './status.service';
import { StorageChart, StorageSlice } from './storage-chart';

const STATES: Record<HealthState, { label: TextKey; badge: string; icon: string }> = {
  HEALTHY: { label: 'status.state.healthy', badge: 'text-bg-success', icon: 'fa-circle-check' },
  UNHEALTHY: { label: 'status.state.unhealthy', badge: 'text-bg-danger', icon: 'fa-circle-xmark' },
  UNKNOWN: {
    label: 'status.state.unknown',
    badge: 'text-bg-secondary',
    icon: 'fa-circle-question',
  },
};

// the name of a storage of the api and its color in the chart, in the palette of the charts of the
// access logs; a storage the page does not know yet shows by its name, in grey
const STORAGES: Record<string, { label: TextKey; color: string }> = {
  files: { label: 'status.storage.names.files', color: '#30c070' },
  buckets: { label: 'status.storage.names.buckets', color: '#c9a300' },
  restic: { label: 'status.storage.names.restic', color: '#4a90d9' },
  database: { label: 'status.storage.names.database', color: '#e0604f' },
};
const OTHER_COLOR = '#7c857f';

// the status of the instance, a maintenance page: the health of the whole system and of every
// health check with the parts each checked, e.g. every domain; and what every storage keeps, as a
// chart and in numbers. Checked again when the page opens and on demand
@Component({
  selector: 'app-status-page',
  imports: [PageHeader, NgTemplateOutlet, TranslatePipe, StorageChart],
  host: { class: 'd-block container pb-5' },
  template: `
    <header appPageHeader back="/" [backLabel]="'shared.all_apps' | t">
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'status.title' | t }}</h1>
      @if (status(); as status) {
        <span
          class="d-flex align-items-center gap-2"
          [attr.aria-label]="'status.overall' | t"
          data-testid="overall"
        >
          <ng-container *ngTemplateOutlet="pill; context: { $implicit: states[status.health] }" />
        </span>
      }
      <button
        type="button"
        class="btn btn-outline-secondary"
        [attr.aria-label]="'status.check_again' | t"
        [title]="'status.check_again' | t"
        [disabled]="checking()"
        (click)="check()"
      >
        <i class="fa-solid fa-rotate" [class.fa-spin]="checking()" aria-hidden="true"></i>
      </button>
    </header>

    <h2 class="h5 mb-3">{{ 'status.health' | t }}</h2>
    @if (healthError(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }
    @if (statuses(); as statuses) {
      <ul class="list-unstyled vstack gap-2 mb-0" [attr.aria-label]="'status.checks' | t">
        @for (status of statuses; track status.name) {
          <li class="card" [attr.data-testid]="'health-' + status.name">
            <div class="card-body d-flex align-items-center gap-3">
              <div class="flex-grow-1 overflow-hidden">
                <span class="fw-semibold">{{ status.name }}</span>
                @if (status.message) {
                  <p class="small text-body-secondary text-break mb-0 mt-1" data-testid="message">
                    {{ status.message }}
                  </p>
                }
              </div>
              <ng-container
                *ngTemplateOutlet="pill; context: { $implicit: states[status.state] }"
              />
            </div>
            @if (status.details.length) {
              <ul class="list-group list-group-flush" [attr.aria-label]="status.name">
                @for (detail of status.details; track detail.name) {
                  <li
                    class="list-group-item d-flex align-items-center gap-3"
                    [attr.data-testid]="'detail-' + detail.name"
                  >
                    <div class="flex-grow-1 overflow-hidden">
                      <span class="font-monospace text-break">{{ detail.name }}</span>
                      @if (detail.message) {
                        <p class="small text-body-secondary text-break mb-0" data-testid="message">
                          {{ detail.message }}
                        </p>
                      }
                    </div>
                    <ng-container
                      *ngTemplateOutlet="pill; context: { $implicit: states[detail.state] }"
                    />
                  </li>
                }
              </ul>
            }
          </li>
        } @empty {
          <li class="card card-body text-center py-4">{{ 'status.none' | t }}</li>
        }
      </ul>
    } @else if (!healthError()) {
      <p class="text-body-secondary">{{ 'status.checking' | t }}</p>
    }

    <h2 class="h5 mt-4 mb-3">{{ 'status.storage.title' | t }}</h2>
    @if (statusError(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }
    @if (status(); as status) {
      @if (status.storage.total; as total) {
        <section class="card card-body" data-testid="storage">
          <div class="row g-4 align-items-center">
            <div class="col-12 col-md-4">
              <app-storage-chart [title]="'status.storage.chart' | t" [slices]="slices()" />
            </div>
            <div class="col-12 col-md-8 table-responsive">
              <table class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col">{{ 'status.storage.name' | t }}</th>
                    <th scope="col" class="text-end">{{ 'status.storage.size' | t }}</th>
                    <th scope="col" class="text-end">{{ 'status.storage.items' | t }}</th>
                    <th scope="col" class="text-end">{{ 'status.storage.counted' | t }}</th>
                  </tr>
                </thead>
                <tbody>
                  @for (storage of status.storage.storages; track storage.name) {
                    <tr [attr.data-testid]="'storage-' + storage.name">
                      <td>
                        <span
                          class="swatch me-2"
                          [style.background-color]="look(storage.name).color"
                          aria-hidden="true"
                        ></span>
                        {{ label(storage.name) }}
                      </td>
                      <td class="text-end font-monospace" data-testid="size">
                        {{ bytes(storage.bytes) }}
                      </td>
                      <td class="text-end font-monospace">{{ storage.items }}</td>
                      <td class="text-end small text-body-secondary">
                        {{ time(storage.computedAt) }}
                      </td>
                    </tr>
                  }
                </tbody>
                <tfoot>
                  <tr class="fw-semibold" data-testid="total">
                    <td>{{ 'status.storage.total' | t }}</td>
                    <td class="text-end font-monospace" data-testid="size">
                      {{ bytes(total.bytes) }}
                    </td>
                    <td class="text-end font-monospace">{{ total.items }}</td>
                    <td class="text-end small text-body-secondary">
                      {{ time(total.computedAt) }}
                    </td>
                  </tr>
                </tfoot>
              </table>
            </div>
          </div>
        </section>
      } @else {
        <p class="text-body-secondary" data-testid="counting">
          {{ 'status.storage.counting' | t }}
        </p>
      }
    } @else if (!statusError()) {
      <p class="text-body-secondary">{{ 'status.checking' | t }}</p>
    }

    <!-- the state as a pill, of the system, of a check or of one of its details -->
    <ng-template #pill let-look>
      <span [class]="'badge flex-shrink-0 ' + look.badge" data-testid="state">
        <i [class]="'fa-solid ' + look.icon" aria-hidden="true"></i>
        {{ look.label | t }}
      </span>
    </ng-template>
  `,
  styles: `
    .swatch {
      display: inline-block;
      width: 0.75rem;
      height: 0.75rem;
      border-radius: 2px;
      vertical-align: baseline;
    }
  `,
})
export class StatusPage {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(StatusService);
  private readonly i18n = inject(I18n);

  protected readonly states = STATES;

  protected readonly statuses = signal<HealthStatus[] | null>(null);
  protected readonly status = signal<Status | null>(null);
  protected readonly healthError = signal<string | null>(null);
  protected readonly statusError = signal<string | null>(null);
  private readonly loadingHealth = signal(false);
  private readonly loadingStatus = signal(false);
  protected readonly checking = computed(() => this.loadingHealth() || this.loadingStatus());

  // every storage as a slice of the chart, the largest first as the api answers
  protected readonly slices = computed<StorageSlice[]>(() =>
    (this.status()?.storage.storages ?? []).map((storage) => ({
      label: this.label(storage.name),
      bytes: storage.bytes,
      color: this.look(storage.name).color,
    })),
  );

  // the loads running, each replaced by the next one
  private healthLoad?: Subscription;
  private statusLoad?: Subscription;

  constructor() {
    this.check();
  }

  protected check(): void {
    this.healthLoad?.unsubscribe();
    this.statusLoad?.unsubscribe();
    this.loadingHealth.set(true);
    this.loadingStatus.set(true);
    const forbidden = { 403: this.i18n.t('status.forbidden_see') };
    this.healthLoad = this.service
      .health()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (statuses) => {
          this.healthError.set(null);
          this.statuses.set(statuses);
          this.loadingHealth.set(false);
        },
        error: (error: unknown) => {
          this.loadingHealth.set(false);
          this.healthError.set(
            apiErrorMessage(error, this.i18n.t('status.not_checked'), forbidden),
          );
        },
      });
    this.statusLoad = this.service
      .status()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (status) => {
          this.statusError.set(null);
          this.status.set(status);
          this.loadingStatus.set(false);
        },
        error: (error: unknown) => {
          this.loadingStatus.set(false);
          this.statusError.set(
            apiErrorMessage(error, this.i18n.t('status.storage.not_loaded'), {
              403: this.i18n.t('status.storage.forbidden'),
            }),
          );
        },
      });
  }

  protected look(name: string): { label: TextKey | null; color: string } {
    return STORAGES[name] ?? { label: null, color: OTHER_COLOR };
  }

  protected label(name: string): string {
    const key = this.look(name).label;
    return key ? this.i18n.t(key) : name;
  }

  protected bytes(value: number): string {
    return formatBytes(value);
  }

  // in the locale of the user (UX-5)
  protected time(value: string): string {
    return new Date(value).toLocaleString();
  }
}
