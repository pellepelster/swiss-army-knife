import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { apiErrorMessage, errorMessages } from '../shared/api';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { enteredDomains, validDomains } from '../shared/domains';
import { BUCKET_NAME_PATTERN, BucketsService } from './buckets.service';
import { DomainList } from './domain-list';

// creates a bucket with its domains on a page of its own, the new bucket opens afterwards
@Component({
  selector: 'app-bucket-create',
  imports: [DomainList, RouterLink, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header class="d-flex flex-wrap align-items-center gap-2 py-3">
      <a
        class="btn btn-link link-body-emphasis fs-4 px-2 py-0 text-decoration-none"
        routerLink="/static-hosting"
        [attr.aria-label]="'hosting.all_buckets' | t"
        ><i class="fa-solid fa-arrow-left" aria-hidden="true"></i
      ></a>
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'hosting.create_page.title' | t }}</h1>
    </header>

    <form class="vstack gap-3 card card-body" (submit)="$event.preventDefault(); create()">
      <div>
        <label class="form-label" for="bucket-name">{{ 'shared.name' | t }}</label>
        <input
          class="form-control font-monospace text-break"
          id="bucket-name"
          placeholder="my-website"
          autocomplete="off"
          autocapitalize="off"
          aria-describedby="bucket-name-hint"
          [attr.aria-invalid]="name() !== '' && !nameValid()"
          [class.is-invalid]="name() !== '' && !nameValid()"
          [value]="name()"
          (input)="name.set($any($event.target).value)"
        />
        <span class="form-text" id="bucket-name-hint">
          {{ 'hosting.create_page.name_hint' | t }}
        </span>
      </div>

      <app-domain-list [(domains)]="domains" />

      @if (errors().length) {
        <ul class="alert alert-danger mb-0" role="alert" data-testid="create-errors">
          @for (message of errors(); track $index) {
            <li>{{ message }}</li>
          }
        </ul>
      }

      <!-- cancel and create are one group (UX-9) -->
      <div class="d-flex justify-content-end">
        <div class="btn-group" role="group" [attr.aria-label]="'shared.form_actions' | t">
          <a class="btn btn-outline-secondary" routerLink="/static-hosting">{{
            'shared.cancel' | t
          }}</a>
          <button class="btn btn-primary" type="submit" [disabled]="!valid() || creating()">
            @if (creating()) {
              <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            } @else {
              <i class="sak-icon-plus" aria-hidden="true"></i>
            }
            {{ 'shared.create' | t }}
          </button>
        </div>
      </div>
    </form>
  `,
})
export class BucketCreate {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(BucketsService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  protected readonly name = signal('');
  protected readonly domains = signal<string[]>([]);
  protected readonly creating = signal(false);
  protected readonly errors = signal<string[]>([]);

  protected readonly nameValid = computed(() => BUCKET_NAME_PATTERN.test(this.name()));
  protected readonly valid = computed(() => this.nameValid() && validDomains(this.domains()));

  protected create(): void {
    if (!this.valid()) return;
    const name = this.name();
    this.creating.set(true);
    this.errors.set([]);
    this.service
      .create(name, enteredDomains(this.domains()))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (bucket) => void this.router.navigate(['/static-hosting', bucket.name]),
        error: (error: unknown) => {
          this.creating.set(false);
          const messages = errorMessages(error);
          this.errors.set(
            messages.length
              ? messages
              : [
                  apiErrorMessage(error, this.i18n.t('hosting.create_page.failed', { name }), {
                    403: this.i18n.t('hosting.create_page.forbidden'),
                  }),
                ],
          );
        },
      });
  }
}
