import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { errorMessages } from '../shared/api';
import { I18n, TranslatePipe } from '../i18n/i18n';
import { enteredDomains, validDomains } from '../shared/domains';
import { bucketErrorMessage } from './bucket-store';
import { BucketsService } from './buckets.service';
import { DomainList } from './domain-list';
import { PageHeader } from '../shared/page-header';

// edits a bucket, not its name; save and cancel go back to the bucket
@Component({
  selector: 'app-bucket-edit',
  imports: [PageHeader, DomainList, RouterLink, TranslatePipe],
  host: { class: 'd-block container pb-5' },
  template: `
    <header
      appPageHeader
      [back]="['/static-hosting', name()]"
      [backLabel]="'hosting.back_to_bucket' | t"
    >
      <h1 class="h3 mb-0 flex-grow-1 text-break">{{ 'hosting.edit_page.title' | t }}</h1>
    </header>

    @if (error(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    @if (loaded()) {
      <form class="vstack gap-3 card card-body" (submit)="$event.preventDefault(); save()">
        <div>
          <label class="form-label" for="edit-bucket-name">{{ 'shared.name' | t }}</label>
          <input
            class="form-control-plaintext font-monospace text-break"
            id="edit-bucket-name"
            [value]="name()"
            readonly
          />
          <span class="form-text">{{ 'hosting.edit_page.name_fixed' | t }}</span>
        </div>

        <app-domain-list [(domains)]="domains" />

        @if (saveErrors().length) {
          <ul class="alert alert-danger mb-0" role="alert" data-testid="save-errors">
            @for (message of saveErrors(); track $index) {
              <li>{{ message }}</li>
            }
          </ul>
        }

        <!-- cancel and save are one group (UX-9) -->
        <div class="d-flex justify-content-end">
          <div class="btn-group" role="group" [attr.aria-label]="'shared.form_actions' | t">
            <a class="btn btn-outline-secondary" [routerLink]="['/static-hosting', name()]">{{
              'shared.cancel' | t
            }}</a>
            <button class="btn btn-primary" type="submit" [disabled]="!valid() || saving()">
              @if (saving()) {
                <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
              }
              {{ 'shared.save' | t }}
            </button>
          </div>
        </div>
      </form>
    } @else if (!error()) {
      <p class="text-body-secondary">{{ 'shared.loading' | t }}</p>
    }
  `,
})
export class BucketEdit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly service = inject(BucketsService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  // the route parameter
  readonly name = input.required<string>();

  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly domains = signal<string[]>([]);
  protected readonly saving = signal(false);
  protected readonly saveErrors = signal<string[]>([]);
  protected readonly valid = computed(() => validDomains(this.domains()));

  constructor() {
    // loads again for another route; a late answer for the bucket before never fills the form
    effect((onCleanup) => {
      const name = this.name();
      untracked(() => {
        this.loaded.set(false);
        this.error.set(null);
        this.saveErrors.set([]);
      });
      const subscription = this.service.bucket(name).subscribe({
        next: (bucket) => {
          this.domains.set([...bucket.domains]);
          this.loaded.set(true);
        },
        error: (error: unknown) =>
          this.error.set(bucketErrorMessage(this.i18n, error, this.i18n.t('hosting.load_failed'))),
      });
      onCleanup(() => subscription.unsubscribe());
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
          this.saveErrors.set(
            messages.length ? messages : [this.i18n.t('hosting.edit_page.save_failed')],
          );
        },
      });
  }
}
