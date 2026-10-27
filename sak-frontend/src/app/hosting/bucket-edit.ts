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
  styleUrls: ['../shared/app-parts.scss', '../shared/app-page.scss'],
  template: `
    <header class="toolbar">
      <a class="back" [routerLink]="['/static-hosting', name()]" aria-label="Back to the bucket"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="title">Edit bucket</h1>
    </header>

    @if (error(); as error) {
      <p class="error" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <form class="form sheet" (submit)="$event.preventDefault(); save()">
        <div class="field">
          <label class="label" for="edit-bucket-name">Name</label>
          <input class="input mono" id="edit-bucket-name" [value]="name()" readonly />
          <span class="hint">The name can not be changed.</span>
        </div>

        <app-domain-list [(domains)]="domains" />

        @if (saveErrors().length) {
          <ul class="form-errors" role="alert" data-testid="save-errors">
            @for (message of saveErrors(); track $index) {
              <li>{{ message }}</li>
            }
          </ul>
        }

        <div class="actions">
          <a class="button button-quiet" [routerLink]="['/static-hosting', name()]">Cancel</a>
          <button class="button" type="submit" [disabled]="!valid() || saving()">
            @if (saving()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            }
            Save
          </button>
        </div>
      </form>
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
