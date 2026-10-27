import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { formatBytes, formatCount } from '../shared/format';
import { BUCKET_NAME_PATTERN, Bucket, BucketsService } from './buckets.service';

// how long to wait before asking again while a bucket is still being counted
export const POLL_INTERVAL_MS = 3000;

// the buckets with their number of objects and size; the numbers are counted in the background
// and can be a few minutes old. A new bucket opens on its own page, where its keys are.
@Component({
  selector: 'app-buckets',
  imports: [DatePipe, ReactiveFormsModule, RouterLink],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="label-mono">S3</div>
        <h1 class="mb-1">Buckets</h1>
        <div class="page-subtitle">
          Counted in the background, recounted every few minutes and after every write.
        </div>
      </div>

      @if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      }

      <form class="card mb-3" (submit)="$event.preventDefault(); create()" aria-label="Add bucket">
        <div class="card-body d-flex flex-wrap align-items-start gap-2">
          <div class="flex-grow-1">
            <label class="visually-hidden" for="bucket-name">Bucket name</label>
            <input
              class="form-control font-mono"
              id="bucket-name"
              placeholder="new-bucket"
              autocomplete="off"
              [formControl]="name"
              [class.is-invalid]="name.dirty && name.invalid && name.value !== ''"
            />
            <div class="invalid-feedback">
              3 to 63 lowercase letters, digits, dots and hyphens, starting and ending with a letter or digit.
            </div>
            @if (createError()) {
              <div class="text-danger small mt-1" role="alert" data-testid="create-error">{{ createError() }}</div>
            }
          </div>
          <button class="btn btn-primary" type="submit" [disabled]="name.invalid || creating()">
            @if (creating()) {
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            }
            Add bucket
          </button>
        </div>
      </form>

      <div class="card">
        <table class="table mb-0" aria-label="Buckets">
          <thead>
            <tr>
              <th scope="col">Bucket</th>
              <th scope="col">Domains</th>
              <th scope="col" class="text-end">Objects</th>
              <th scope="col" class="text-end">Size</th>
              <th scope="col">Counted</th>
            </tr>
          </thead>
          <tbody>
            @for (bucket of buckets(); track bucket.name) {
              <tr [attr.data-testid]="'bucket-' + bucket.name">
                <td class="font-mono">
                  <a [routerLink]="['/s3', bucket.name]">{{ bucket.name }}</a>
                </td>
                <td class="font-mono">{{ bucket.domains.join(', ') || '–' }}</td>
                @if (bucket.statistics.failed) {
                  <td colspan="3" class="text-secondary">could not be counted</td>
                } @else if (bucket.statistics.computing) {
                  <td colspan="3" class="text-secondary">
                    <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                    counting…
                  </td>
                } @else {
                  <td class="font-mono text-end" data-testid="objects">{{ formatCount(bucket.statistics.objects!) }}</td>
                  <td class="font-mono text-end" data-testid="bytes">{{ formatBytes(bucket.statistics.bytes!) }}</td>
                  <td class="text-secondary small">{{ bucket.statistics.computedAt | date: 'HH:mm:ss' }}</td>
                }
              </tr>
            } @empty {
              <tr>
                <td colspan="5" class="text-center text-secondary py-4">No buckets.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  `,
})
export class Buckets {
  private readonly service = inject(BucketsService);
  private readonly router = inject(Router);

  protected readonly buckets = signal<Bucket[]>([]);
  protected readonly error = signal<string | null>(null);

  protected readonly name = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.pattern(BUCKET_NAME_PATTERN)],
  });
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
    this.load();
  }

  protected create(): void {
    const name = this.name.value;
    this.creating.set(true);
    this.createError.set(null);
    this.subscriptions.add(
      this.service.create(name).subscribe({
        next: (bucket) => void this.router.navigate(['/s3', bucket.name]),
        error: (error: unknown) => {
          this.creating.set(false);
          this.createError.set(createErrorMessage(error, name));
        },
      }),
    );
  }

  // asks again after a while as long as a bucket is still being counted
  private load(): void {
    this.subscriptions.add(
      this.service.buckets().subscribe({
        next: (buckets) => {
          this.buckets.set(buckets);
          if (buckets.some((bucket) => bucket.statistics.computing)) {
            this.subscriptions.add(timer(POLL_INTERVAL_MS).subscribe(() => this.load()));
          }
        },
        error: (error: unknown) =>
          this.error.set(
            error instanceof HttpErrorResponse && error.status === 403
              ? 'Your role does not allow to see the buckets.'
              : 'The buckets could not be loaded.',
          ),
      }),
    );
  }
}

function createErrorMessage(error: unknown, name: string): string {
  if (!(error instanceof HttpErrorResponse)) return 'The bucket could not be created.';
  switch (error.status) {
    case 409:
      return `A bucket named ${name} exists already.`;
    case 400:
      return `${name} is not a valid bucket name, or one reserved for the console.`;
    case 403:
      return 'Your role does not allow to create buckets.';
    default:
      return 'The bucket could not be created.';
  }
}
