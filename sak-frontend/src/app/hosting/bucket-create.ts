import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { errorMessages } from '../shared/api';
import { enteredDomains, validDomains } from '../shared/domains';
import { BUCKET_NAME_PATTERN, BucketsService } from './buckets.service';
import { DomainList } from './domain-list';

// creates a bucket with its domains on a page of its own, the new bucket opens afterwards
@Component({
  selector: 'app-bucket-create',
  imports: [DomainList, RouterLink],
  styleUrls: ['../shared/app-parts.scss', '../shared/app-page.scss'],
  template: `
    <header class="toolbar">
      <a class="back" routerLink="/static-hosting" aria-label="All buckets"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="title">Create bucket</h1>
    </header>

    <form class="form sheet" (submit)="$event.preventDefault(); create()">
      <div class="field">
        <label class="label" for="bucket-name">Name</label>
        <input
          class="input mono"
          id="bucket-name"
          placeholder="my-website"
          autocomplete="off"
          autocapitalize="off"
          aria-describedby="bucket-name-hint"
          [attr.aria-invalid]="name() !== '' && !nameValid()"
          [value]="name()"
          (input)="name.set($any($event.target).value)"
        />
        <span class="hint" id="bucket-name-hint">
          3 to 63 lowercase letters, digits, dots and hyphens, starting and ending with a letter or
          digit. The name can not be changed later.
        </span>
      </div>

      <app-domain-list [(domains)]="domains" />

      @if (errors().length) {
        <ul class="form-errors" role="alert" data-testid="create-errors">
          @for (message of errors(); track $index) {
            <li>{{ message }}</li>
          }
        </ul>
      }

      <div class="actions">
        <a class="button button-quiet" routerLink="/static-hosting">Cancel</a>
        <button class="button" type="submit" [disabled]="!valid() || creating()">
          @if (creating()) {
            <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
          }
          Create bucket
        </button>
      </div>
    </form>
  `,
})
export class BucketCreate {
  private readonly service = inject(BucketsService);
  private readonly router = inject(Router);

  protected readonly name = signal('');
  protected readonly domains = signal<string[]>([]);
  protected readonly creating = signal(false);
  protected readonly errors = signal<string[]>([]);

  protected readonly nameValid = computed(() => BUCKET_NAME_PATTERN.test(this.name()));
  protected readonly valid = computed(() => this.nameValid() && validDomains(this.domains()));

  private readonly subscriptions = new Subscription();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscriptions.unsubscribe());
  }

  protected create(): void {
    if (!this.valid()) return;
    const name = this.name();
    this.creating.set(true);
    this.errors.set([]);
    this.subscriptions.add(
      this.service.create(name, enteredDomains(this.domains())).subscribe({
        next: (bucket) => void this.router.navigate(['/static-hosting', bucket.name]),
        error: (error: unknown) => {
          this.creating.set(false);
          const messages = errorMessages(error);
          this.errors.set(
            messages.length
              ? messages
              : error instanceof HttpErrorResponse && error.status === 403
                ? ['Your role does not allow to create buckets.']
                : [`The bucket ${name} could not be created.`],
          );
        },
      }),
    );
  }
}
