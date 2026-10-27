import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { errorMessages } from '../shared/api';
import { DomainList } from '../shared/domain-list';
import { enteredDomains, validDomains } from '../shared/domains';
import { formatBytes, formatCount } from '../shared/format';
import { Modal } from '../shared/modal';
import { BUCKET_NAME_PATTERN, Bucket, BucketsService } from './buckets.service';

// how long to wait before asking again while a bucket is still being counted
export const POLL_INTERVAL_MS = 3000;

// the buckets with their number of objects and size; the numbers are counted in the background
// and can be a few minutes old. A new bucket, added in a dialog, opens on its own page, where its keys are.
@Component({
  selector: 'app-buckets',
  imports: [DatePipe, DomainList, Modal, ReactiveFormsModule, RouterLink],
  template: `
    <div class="page">
      <div class="page-header d-flex flex-wrap align-items-end justify-content-between gap-2">
        <div>
          <div class="label-mono">S3</div>
          <h1 class="mb-1">Buckets</h1>
          <div class="page-subtitle">
            Counted in the background, recounted every few minutes and after every write.
          </div>
        </div>
        <button class="btn btn-primary" type="button" (click)="openDialog()">Add bucket</button>
      </div>

      @if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      }

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
                  <a [routerLink]="['/s3/buckets', bucket.name]">{{ bucket.name }}</a>
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

    @if (dialogOpen()) {
      <app-modal title="Add bucket" (closed)="dialogOpen.set(false)">
        <form (submit)="$event.preventDefault(); create()">
          <div class="modal-body">
            <div class="mb-3">
              <label class="form-label" for="bucket-name">Bucket name</label>
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
              <div class="form-text">The name can not be changed later.</div>
            </div>
            <app-domain-list [(domains)]="domains" />
            @if (createErrors().length) {
              <div class="text-danger small mt-2" role="alert" data-testid="create-error">
                @for (message of createErrors(); track $index) {
                  <div>{{ message }}</div>
                }
              </div>
            }
          </div>
          <div class="modal-footer">
            <button class="btn btn-outline-secondary" type="button" (click)="dialogOpen.set(false)">Cancel</button>
            <button class="btn btn-primary" type="submit" [disabled]="!nameValid() || !validDomains(domains()) || creating()">
              @if (creating()) {
                <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
              }
              Add bucket
            </button>
          </div>
        </form>
      </app-modal>
    }
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
  // the name's validity as a signal, the dialog's footer reads it
  protected readonly nameValid = signal(false);
  protected readonly domains = signal<string[]>([]);
  protected readonly dialogOpen = signal(false);
  protected readonly creating = signal(false);
  protected readonly createErrors = signal<string[]>([]);
  protected readonly validDomains = validDomains;

  protected readonly formatBytes = formatBytes;
  protected readonly formatCount = formatCount;

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
    this.subscriptions.add(this.name.statusChanges.subscribe(() => this.nameValid.set(this.name.valid)));
    this.load();
  }

  protected openDialog(): void {
    this.name.reset();
    this.domains.set(['']);
    this.createErrors.set([]);
    this.dialogOpen.set(true);
  }

  protected create(): void {
    if (!this.name.valid || !validDomains(this.domains())) return;
    const name = this.name.value;
    this.creating.set(true);
    this.createErrors.set([]);
    this.subscriptions.add(
      this.service.create(name, enteredDomains(this.domains())).subscribe({
        next: (bucket) => void this.router.navigate(['/s3/buckets', bucket.name]),
        error: (error: unknown) => {
          this.creating.set(false);
          this.createErrors.set(createErrorMessages(error, name));
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

// what the api answered, or what its status tells
function createErrorMessages(error: unknown, name: string): string[] {
  const messages = errorMessages(error);
  if (messages.length) return messages;
  if (error instanceof HttpErrorResponse && error.status === 403) return ['Your role does not allow to create buckets.'];
  return [`The bucket ${name} could not be created.`];
}
