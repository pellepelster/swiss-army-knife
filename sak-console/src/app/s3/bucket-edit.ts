import { Component, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { errorMessages } from '../shared/api';
import { DomainList } from '../shared/domain-list';
import { enteredDomains, validDomains } from '../shared/domains';
import { errorMessage } from './bucket-store';
import { BucketsService } from './buckets.service';

// edits everything of a bucket that can be changed, its name can not; saving or cancelling
// goes back to the bucket
@Component({
  selector: 'app-bucket-edit',
  imports: [DomainList, RouterLink],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="label-mono">
          <a routerLink="/s3/buckets">S3 · Buckets</a> ·
          <a [routerLink]="['/s3/buckets', name()]">{{ name() }}</a>
        </div>
        <h1 class="mb-1">Edit bucket</h1>
      </div>

      @if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      }

      @if (loaded()) {
        <form class="card" (submit)="$event.preventDefault(); save()">
          <div class="card-body">
            <div class="mb-3">
              <label class="form-label" for="edit-bucket-name">Bucket name</label>
              <input class="form-control font-mono" id="edit-bucket-name" [value]="name()" readonly />
              <div class="form-text">The name can not be changed.</div>
            </div>
            <app-domain-list [(domains)]="domains" />
            @if (saveErrors().length) {
              <div class="text-danger small mt-2" role="alert" data-testid="save-error">
                @for (message of saveErrors(); track $index) {
                  <div>{{ message }}</div>
                }
              </div>
            }
          </div>
          <div class="card-footer d-flex justify-content-end gap-2">
            <a class="btn btn-outline-secondary" [routerLink]="['/s3/buckets', name()]">Cancel</a>
            <button class="btn btn-primary" type="submit" [disabled]="!validDomains(domains()) || saving()">
              @if (saving()) {
                <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
              }
              Save
            </button>
          </div>
        </form>
      }
    </div>
  `,
})
export class BucketEdit implements OnInit {
  private readonly service = inject(BucketsService);
  private readonly router = inject(Router);

  // the route parameter
  readonly name = input.required<string>();

  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly domains = signal<string[]>([]);
  protected readonly saving = signal(false);
  protected readonly saveErrors = signal<string[]>([]);
  protected readonly validDomains = validDomains;

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
  }

  ngOnInit(): void {
    this.subscriptions.add(
      this.service.bucket(this.name()).subscribe({
        next: (bucket) => {
          this.domains.set(bucket.domains.length ? [...bucket.domains] : ['']);
          this.loaded.set(true);
        },
        error: (error: unknown) => this.error.set(errorMessage(error, 'The bucket could not be loaded.')),
      }),
    );
  }

  protected save(): void {
    if (!validDomains(this.domains())) return;
    this.saving.set(true);
    this.saveErrors.set([]);
    this.subscriptions.add(
      this.service.updateDomains(this.name(), enteredDomains(this.domains())).subscribe({
        next: () => void this.router.navigate(['/s3/buckets', this.name()]),
        error: (error: unknown) => {
          this.saving.set(false);
          const messages = errorMessages(error);
          this.saveErrors.set(messages.length ? messages : ['The bucket could not be saved.']);
        },
      }),
    );
  }
}
