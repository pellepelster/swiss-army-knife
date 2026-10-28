import { Component, computed, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { errorMessages } from '../shared/api';
import { enteredDomains, validDomains } from '../shared/domains';
import { bucketErrorMessage } from './bucket-store';
import { BucketsService } from './buckets.service';
import { DomainList } from './domain-list';

// edits everything of a bucket that can be changed on a page of its own, its name can not be;
// saving or cancelling goes back to the bucket
@Component({
  selector: 'app-bucket-edit',
  imports: [DomainList, RouterLink],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        [routerLink]="['/static-hosting', name()]"
        aria-label="Back to the bucket"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">Edit bucket</h1>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <form class="vstack gap-3 card card-body" (submit)="$event.preventDefault(); save()">
        <div>
          <label class="form-label" for="edit-bucket-name">Name</label>
          <input
            class="form-control-plaintext font-monospace text-break"
            id="edit-bucket-name"
            [value]="name()"
            readonly
          />
          <span class="form-text">The name can not be changed.</span>
        </div>

        <app-domain-list [(domains)]="domains" />

        @if (saveErrors().length) {
          <ul class="alert alert-danger mb-0" role="alert" data-testid="save-errors">
            @for (message of saveErrors(); track $index) {
              <li>{{ message }}</li>
            }
          </ul>
        }

        <div class="d-flex flex-wrap justify-content-end gap-2">
          <a class="btn btn-outline-secondary" [routerLink]="['/static-hosting', name()]">Cancel</a>
          <button class="btn btn-primary" type="submit" [disabled]="!valid() || saving()">
            @if (saving()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            }
            Save
          </button>
        </div>
      </form>
    } @else if (!error()) {
      <p class="text-body-secondary">Loading…</p>
    }
  `,
})
export class BucketEdit implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(BucketsService);
  private readonly router = inject(Router);

  // the route parameter
  readonly name = input.required<string>();

  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly domains = signal<string[]>([]);
  protected readonly saving = signal(false);
  protected readonly saveErrors = signal<string[]>([]);
  protected readonly valid = computed(() => validDomains(this.domains()));

  ngOnInit(): void {
    this.service
      .bucket(this.name())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (bucket) => {
          this.domains.set([...bucket.domains]);
          this.loaded.set(true);
        },
        error: (error: unknown) =>
          this.error.set(bucketErrorMessage(error, 'The bucket could not be loaded.')),
      });
  }

  protected save(): void {
    if (!this.valid()) return;
    this.saving.set(true);
    this.saveErrors.set([]);
    this.service
      .updateDomains(this.name(), enteredDomains(this.domains()))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigate(['/static-hosting', this.name()]),
        error: (error: unknown) => {
          this.saving.set(false);
          const messages = errorMessages(error);
          this.saveErrors.set(messages.length ? messages : ['The bucket could not be saved.']);
        },
      });
  }
}
