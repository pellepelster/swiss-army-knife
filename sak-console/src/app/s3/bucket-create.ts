import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { errorMessages } from '../shared/api';
import { DomainList } from '../shared/domain-list';
import { enteredDomains, validDomains } from '../shared/domains';
import { BUCKET_NAME_PATTERN, BucketsService } from './buckets.service';

// adds a bucket with its domains; the new bucket opens on its own page, where its keys are
@Component({
  selector: 'app-bucket-create',
  imports: [DomainList, ReactiveFormsModule, RouterLink],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="label-mono"><a routerLink="/s3/buckets">S3 · Buckets</a></div>
        <h1 class="mb-1">Add bucket</h1>
      </div>

      <form class="card" (submit)="$event.preventDefault(); create()">
        <div class="card-body">
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
        <div class="card-footer d-flex justify-content-end gap-2">
          <a class="btn btn-outline-secondary" routerLink="/s3/buckets">Cancel</a>
          <button
            class="btn btn-primary"
            type="submit"
            [disabled]="!nameValid() || !validDomains(domains()) || creating()"
          >
            @if (creating()) {
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            }
            Add bucket
          </button>
        </div>
      </form>
    </div>
  `,
})
export class BucketCreate {
  private readonly service = inject(BucketsService);
  private readonly router = inject(Router);

  protected readonly name = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.pattern(BUCKET_NAME_PATTERN)],
  });
  // the name's validity as a signal, the footer reads it
  protected readonly nameValid = signal(false);
  protected readonly domains = signal<string[]>([]);
  protected readonly creating = signal(false);
  protected readonly createErrors = signal<string[]>([]);
  protected readonly validDomains = validDomains;

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
    this.subscriptions.add(this.name.statusChanges.subscribe(() => this.nameValid.set(this.name.valid)));
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
}

// what the api answered, or what its status tells
function createErrorMessages(error: unknown, name: string): string[] {
  const messages = errorMessages(error);
  if (messages.length) return messages;
  if (error instanceof HttpErrorResponse && error.status === 403)
    return ['Your role does not allow to create buckets.'];
  return [`The bucket ${name} could not be created.`];
}
